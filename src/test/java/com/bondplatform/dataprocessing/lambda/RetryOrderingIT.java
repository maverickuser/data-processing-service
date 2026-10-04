package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.application.DatasetHandler;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.application.RunJob;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionOperations;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Test cases I-CSV-06, I-CSV-07, and I-CSV-08 with a test dataset handler: retries through a real
 * FIFO queue with a dead-letter queue, PostgreSQL, and the worker's entry point. The retry delays
 * are shortened to seconds; the queue is polled here as Lambda's event source mapping would.
 */
@Import(RetryOrderingIT.ScriptedHandlerConfiguration.class)
class RetryOrderingIT extends PostgresIntegrationTest {

  private static final SqsClient SQS = ElasticMq.client();
  private static final String DEAD_LETTERS = fifoQueue("retry-dead-letters.fifo", Map.of());
  private static final String JOBS =
      fifoQueue(
          "retry-jobs.fifo",
          Map.of(
              QueueAttributeName.VISIBILITY_TIMEOUT,
              "20",
              QueueAttributeName.REDRIVE_POLICY,
              "{\"maxReceiveCount\":\"3\",\"deadLetterTargetArn\":\""
                  + arnOf(DEAD_LETTERS)
                  + "\"}"));
  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private RunJob runJob;
  @Autowired private ScriptedHandler handler;

  private WorkerPump pump;

  @DynamicPropertySource
  static void useTestQueuesAndShortDelays(DynamicPropertyRegistry registry) {
    registry.add("data-processing.queues.endpoint", () -> ElasticMq.endpoint().toString());
    registry.add("data-processing.queues.file-processing-url", () -> JOBS);
    registry.add("data-processing.worker.retry-delays", () -> "1s,2s");
  }

  @BeforeEach
  void startWorkerOnEmptyQueues() {
    SQS.purgeQueue(request -> request.queueUrl(JOBS));
    SQS.purgeQueue(request -> request.queueUrl(DEAD_LETTERS));
    handler.reset();
    pump = new WorkerPump(SQS, JOBS, runJob);
  }

  // I-CSV-06, I-CSV-07
  @Test
  void retriedJobKeepsItsPlaceInItsGroupWhileAnotherGroupProceeds() {
    JobId first = admit("run_a1", "INE121A07QY9");
    final JobId second = admit("run_a2", "INE121A07QY9");
    final JobId otherGroup = admit("run_b1", "INE002A08534");
    handler.failTemporarily(first, 1);

    pump.until(() -> handler.completed.size() == 3);

    assertThat(handler.completed).containsSubsequence(first, second);
    assertThat(handler.completed.indexOf(otherGroup)).isLessThan(handler.completed.indexOf(first));
    assertThat(statusOf(first)).isEqualTo("COMPLETED");
    assertThat(runsOf(first)).containsExactly("FAILED_TEMPORARY", "SUCCEEDED");
    assertThat(
            jdbc.sql("SELECT counts::text FROM data_processing.ingestion_requests WHERE id = :id")
                .param("id", first.value())
                .query(String.class)
                .single())
        .isEqualTo("{\"attempt\": 2}");
    assertThat(
            jdbc.sql("SELECT count(*) FROM data_processing.ingestion_requests")
                .query(Long.class)
                .single())
        .isEqualTo(3);
  }

  // I-CSV-08
  @Test
  void jobFailingThreeTimesIsFailedAndDeadLetteredAndTheNextJobOfItsGroupRuns() {
    JobId failing = admit("run_c1", "INE121A07QY9");
    JobId next = admit("run_c2", "INE121A07QY9");
    handler.failTemporarily(failing, 3);

    pump.until(() -> handler.completed.contains(next));

    assertThat(statusOf(failing)).isEqualTo("FAILED");
    assertThat(runsOf(failing))
        .containsExactly("FAILED_TEMPORARY", "FAILED_TEMPORARY", "FAILED_TEMPORARY");
    assertThat(statusOf(next)).isEqualTo("COMPLETED");
    List<Message> deadLettered =
        SQS.receiveMessage(request -> request.queueUrl(DEAD_LETTERS).waitTimeSeconds(2)).messages();
    assertThat(deadLettered)
        .singleElement()
        .satisfies(message -> assertThat(message.body()).contains(failing.toString()));
  }

  private JobId admit(String runId, String isin) {
    Map<String, Object> event = SubmissionEvents.nsdl(runId, isin);
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
  }

  private String statusOf(JobId job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.value())
        .query(String.class)
        .single();
  }

  private List<String> runsOf(JobId job) {
    return jdbc.sql(
            "SELECT status FROM data_processing.processing_runs"
                + " WHERE ingestion_request_id = :id ORDER BY attempt_number")
        .param("id", job.value())
        .query(String.class)
        .list();
  }

  private static String fifoQueue(String name, Map<QueueAttributeName, String> attributes) {
    Map<QueueAttributeName, String> all = new HashMap<>(attributes);
    all.put(QueueAttributeName.FIFO_QUEUE, "true");
    return SQS.createQueue(request -> request.queueName(name).attributes(all)).queueUrl();
  }

  private static String arnOf(String queueUrl) {
    return Objects.requireNonNull(
        SQS.getQueueAttributes(
                request -> request.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN));
  }

  /** A dataset handler that fails a job's first attempts temporarily when told to. */
  static final class ScriptedHandler implements DatasetHandler {

    private final JobCompletion completion;
    private final TransactionOperations transactions;
    private final Map<JobId, Integer> failuresLeft = new ConcurrentHashMap<>();
    private final List<JobId> completed = new CopyOnWriteArrayList<>();

    ScriptedHandler(JobCompletion completion, TransactionOperations transactions) {
      this.completion = completion;
      this.transactions = transactions;
    }

    void reset() {
      failuresLeft.clear();
      completed.clear();
    }

    void failTemporarily(JobId job, int times) {
      failuresLeft.put(job, times);
    }

    @Override
    public DatasetUrn dataset() {
      return new DatasetUrn("urn:bond-platform:dataset:nsdl-security");
    }

    @Override
    public void process(ClaimedJob claimed) {
      JobId job = claimed.job().id();
      if (failuresLeft.getOrDefault(job, 0) > 0) {
        failuresLeft.merge(job, -1, Integer::sum);
        throw new TemporaryFailureException(
            "SOURCE_UNAVAILABLE", "injected", new IllegalStateException("injected"));
      }
      transactions.executeWithoutResult(
          status ->
              completion.complete(
                  claimed,
                  new JobOutcome(
                      JobStatus.COMPLETED, "{\"attempt\": " + claimed.attemptNumber() + "}", 0)));
      completed.add(job);
    }
  }

  /** Adds the scripted handler to the application. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedHandlerConfiguration {

    @Bean
    ScriptedHandler scriptedHandler(JobCompletion completion, TransactionOperations transactions) {
      return new ScriptedHandler(completion, transactions);
    }
  }
}
