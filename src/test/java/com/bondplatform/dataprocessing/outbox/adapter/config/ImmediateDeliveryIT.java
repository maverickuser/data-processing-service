package com.bondplatform.dataprocessing.outbox.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmissionReceipt;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.outbox.adapter.aws.ElasticMq;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * LLD section 23.4 end to end: a committed admission is sent to the job queue before the use case
 * returns, and the sweep delivers what is left. PostgreSQL and an SQS-compatible server.
 */
class ImmediateDeliveryIT extends PostgresIntegrationTest {

  private static final SqsClient SQS = ElasticMq.client();
  private static final String JOBS =
      SQS.createQueue(
              request ->
                  request
                      .queueName("immediate-delivery-jobs.fifo")
                      .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
          .queueUrl();
  private static final String DETAILS =
      SQS.createQueue(request -> request.queueName("immediate-delivery-details")).queueUrl();

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private OutboxEventStore outbox;
  @Autowired private OutboxDispatcher dispatcher;

  @DynamicPropertySource
  static void useTestQueues(DynamicPropertyRegistry registry) {
    registry.add("data-processing.queues.endpoint", () -> ElasticMq.endpoint().toString());
    registry.add("data-processing.queues.file-processing-url", () -> JOBS);
    registry.add("data-processing.queues.security-details-url", () -> DETAILS);
  }

  @Test
  void acceptedSubmissionIsOnTheJobQueueWhenAdmissionReturns() {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");

    AdmissionReceipt receipt = admitSubmission.admit(SubmissionEvents.submissionOf(event), event);

    List<Message> queued = receive(JOBS);
    assertThat(queued)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.body()).isEqualTo("{\"jobId\":\"" + receipt.jobId() + "\"}");
              assertThat(message.attributes())
                  .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, "isin:INE121A07QY9");
            });
    assertThat(
            jdbc.sql("SELECT status FROM data_processing.outbox_events")
                .query(String.class)
                .single())
        .isEqualTo("DELIVERED");
  }

  @Test
  void sweepDeliversEventRecordedOutsideTransaction() {
    outbox.append(
        new NewOutboxEvent(
            UUID.randomUUID(),
            OutboxDestination.SECURITY_DETAILS,
            null,
            null,
            "{\"isin\":\"INE121A07QY9\"}",
            Instant.parse("2026-09-27T14:31:00Z")));
    assertThat(receive(DETAILS)).isEmpty();

    assertThat(dispatcher.sweep()).isEqualTo(1);

    assertThat(receive(DETAILS))
        .extracting(Message::body)
        .containsExactly("{\"isin\":\"INE121A07QY9\"}");
  }

  private static List<Message> receive(String queue) {
    List<Message> messages =
        SQS.receiveMessage(
                request ->
                    request
                        .queueUrl(queue)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .messageSystemAttributeNames(MessageSystemAttributeName.ALL))
            .messages();
    messages.forEach(
        message ->
            SQS.deleteMessage(
                request -> request.queueUrl(queue).receiptHandle(message.receiptHandle())));
    return messages;
  }
}
