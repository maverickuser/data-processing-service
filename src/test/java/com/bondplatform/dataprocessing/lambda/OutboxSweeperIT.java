package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.operations.application.OutboxBacklogMonitor;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.application.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-EVT-05 and the sweeper's exit evidence (LLD section 23.4): jobs admitted while the job queue
 * cannot be reached stay pending, and the sweeper Lambda later delivers each once, in order, with
 * its stored ID and payload. I-OPS-05 for the sweeper function: it logs its run and records how
 * many stuck jobs it failed and how old the oldest pending event is.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxSweeperIT extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SqsClient SQS = ElasticMq.client();
  private static final String QUEUE_NAME = "sweeper-jobs.fifo";

  /** The job queue's URL; the queue itself is created only once the jobs are admitted. */
  private static final String JOBS =
      SQS.createQueue(request -> request.queueName("sweeper-probe"))
          .queueUrl()
          .replace("sweeper-probe", QUEUE_NAME);

  private static final String DETAILS =
      SQS.createQueue(request -> request.queueName("sweeper-details")).queueUrl();

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private OutboxDispatcher dispatcher;
  @Autowired private StuckJobFailer failer;
  @Autowired private OutboxBacklogMonitor backlog;
  @Autowired private Metrics metrics;

  @DynamicPropertySource
  static void useTestQueues(DynamicPropertyRegistry registry) {
    registry.add("data-processing.queues.endpoint", () -> ElasticMq.endpoint().toString());
    registry.add("data-processing.queues.file-processing-url", () -> JOBS);
    registry.add("data-processing.queues.security-details-url", () -> DETAILS);
  }

  @Test
  void pendingJobsLeftByAnUnreachableQueueAreDeliveredOnceInOrder(CapturedOutput output) {
    List<String> jobs = new ArrayList<>();
    for (String run : List.of("run_301", "run_302", "run_303")) {
      Map<String, Object> event = SubmissionEvents.nsdl(run, "INE121A07QY9");
      jobs.add(
          admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId().toString());
    }
    assertThat(statuses()).containsOnly("PENDING").hasSize(3);
    final List<Map<String, Object>> stored =
        jdbc.sql(
                """
                SELECT id::text AS id, payload::text AS payload
                FROM data_processing.outbox_events ORDER BY ordering_key
                """)
            .query()
            .listOfRows();

    SQS.createQueue(
        request ->
            request
                .queueName(QUEUE_NAME)
                .attributes(
                    Map.of(
                        QueueAttributeName.FIFO_QUEUE,
                        "true",
                        QueueAttributeName.VISIBILITY_TIMEOUT,
                        "60")));
    // As if the failed first send's backoff had passed; an hour back, so a database clock that
    // runs ahead of the JVM's cannot leave the events not yet due
    jdbc.sql(
            "UPDATE data_processing.outbox_events"
                + " SET next_attempt_at = now() - INTERVAL '1 hour',"
                + " created_at = now() - INTERVAL '20 minutes'")
        .update();
    assertThat(backlog.oldestPendingAge())
        .isBetween(Duration.ofMinutes(19), Duration.ofMinutes(21));
    OutboxSweeperHandler sweeper =
        new OutboxSweeperHandler(
            failer::failStuckJobs, dispatcher::sweep, backlog::oldestPendingAge, metrics);

    assertThat(sweeper.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("failed stuckJobs=0 delivered outboxEvents=3");
    assertThat(sweeper.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("failed stuckJobs=0 delivered outboxEvents=0");

    List<Message> received = receiveAll();
    assertThat(received)
        .extracting(message -> JSON.readTree(message.body()).path("jobId").asString())
        .containsExactlyElementsOf(jobs);
    for (int i = 0; i < received.size(); i++) {
      Message message = received.get(i);
      assertThat(message.attributes())
          .containsEntry(
              MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID,
              String.valueOf(stored.get(i).get("id")))
          .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, "isin:INE121A07QY9");
      assertThat(JSON.readTree(message.body()))
          .isEqualTo(JSON.readTree(String.valueOf(stored.get(i).get("payload"))));
    }
    assertThat(statuses()).containsOnly("DELIVERED");
    assertThat(JsonLines.metric(output, "StuckJobsFailed"))
        .extracting(line -> line.path("StuckJobsFailed").asLong())
        .containsExactly(0L, 0L);
    assertThat(JsonLines.metric(output, "OldestPendingOutboxAge"))
        .extracting(line -> line.path("OldestPendingOutboxAge").asLong())
        .containsExactly(0L, 0L);
    assertThat(JsonLines.log(output, "Sweep done").path("level").asString()).isEqualTo("INFO");
  }

  private List<String> statuses() {
    return jdbc.sql("SELECT status FROM data_processing.outbox_events").query(String.class).list();
  }

  /** Receives and deletes until the queue is empty; a FIFO group yields one batch at a time. */
  private static List<Message> receiveAll() {
    List<Message> all = new ArrayList<>();
    List<Message> batch;
    do {
      batch =
          SQS.receiveMessage(
                  request ->
                      request
                          .queueUrl(JOBS)
                          .maxNumberOfMessages(10)
                          .waitTimeSeconds(1)
                          .messageSystemAttributeNames(MessageSystemAttributeName.ALL))
              .messages();
      for (Message message : batch) {
        SQS.deleteMessage(request -> request.queueUrl(JOBS).receiptHandle(message.receiptHandle()));
      }
      all.addAll(batch);
    } while (!batch.isEmpty());
    return all;
  }
}
