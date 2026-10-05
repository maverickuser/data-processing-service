package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * its stored ID and payload.
 */
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

  @DynamicPropertySource
  static void useTestQueues(DynamicPropertyRegistry registry) {
    registry.add("data-processing.queues.endpoint", () -> ElasticMq.endpoint().toString());
    registry.add("data-processing.queues.file-processing-url", () -> JOBS);
    registry.add("data-processing.queues.security-details-url", () -> DETAILS);
  }

  @Test
  void pendingJobsLeftByAnUnreachableQueueAreDeliveredOnceInOrder() {
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
    // As if the failed first send's backoff had passed
    jdbc.sql("UPDATE data_processing.outbox_events SET next_attempt_at = now()").update();
    OutboxSweeperHandler sweeper = new OutboxSweeperHandler(dispatcher::sweep);

    assertThat(sweeper.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("delivered outboxEvents=3");
    assertThat(sweeper.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("delivered outboxEvents=0");

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
