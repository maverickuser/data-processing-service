package com.bondplatform.dataprocessing.outbox.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.outbox.application.QueuePublishException;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** The SQS publisher against an SQS-compatible server. */
@Testcontainers(disabledWithoutDocker = true)
class SqsQueuePublisherIT {

  private static SqsClient sqs;
  private static URI fifoQueue;
  private static URI standardQueue;
  private static SqsQueuePublisher publisher;

  @BeforeAll
  static void createQueues() {
    sqs = ElasticMq.client();
    fifoQueue =
        URI.create(
            sqs.createQueue(
                    request ->
                        request
                            .queueName("publisher-test.fifo")
                            .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
                .queueUrl());
    standardQueue =
        URI.create(sqs.createQueue(request -> request.queueName("publisher-test")).queueUrl());
    publisher =
        new SqsQueuePublisher(
            sqs,
            Map.of(
                OutboxDestination.FILE_PROCESSING,
                fifoQueue,
                OutboxDestination.SECURITY_DETAILS,
                standardQueue));
  }

  @AfterAll
  static void closeClient() {
    sqs.close();
  }

  @Test
  void fifoMessagesKeepTheirGroupOrderAndAreDeduplicatedByEventId() {
    OutboxEvent first = event(OutboxDestination.FILE_PROCESSING, "trade-date:2026-09-21", 1);
    OutboxEvent second = event(OutboxDestination.FILE_PROCESSING, "trade-date:2026-09-21", 2);

    publisher.publish(first);
    publisher.publish(first);
    publisher.publish(second);

    List<Message> received = receiveAll(fifoQueue);
    assertThat(received).extracting(Message::body).containsExactly(body(1), body(2));
    assertThat(received.get(0).attributes())
        .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, "trade-date:2026-09-21")
        .containsEntry(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID, first.id().toString());
  }

  @Test
  void standardQueueReceivesTheBody() {
    publisher.publish(event(OutboxDestination.SECURITY_DETAILS, null, 3));

    assertThat(receiveAll(standardQueue)).extracting(Message::body).containsExactly(body(3));
  }

  @Test
  void unreachableQueueIsPublishFailure() {
    try (SqsClient nowhere =
        SqsClient.builder()
            .region(Region.AP_SOUTH_1)
            .endpointOverride(URI.create("http://127.0.0.1:1"))
            .build()) {
      SqsQueuePublisher unreachable =
          new SqsQueuePublisher(
              nowhere,
              Map.of(
                  OutboxDestination.FILE_PROCESSING,
                  fifoQueue,
                  OutboxDestination.SECURITY_DETAILS,
                  standardQueue));

      assertThatThrownBy(
              () -> unreachable.publish(event(OutboxDestination.SECURITY_DETAILS, null, 4)))
          .isInstanceOf(QueuePublishException.class)
          .hasMessage("Sending to SECURITY_DETAILS failed: SdkClientException");
    }
  }

  /** Receives until the queue is empty, deleting what it receives. */
  private static List<Message> receiveAll(URI queue) {
    List<Message> all = new ArrayList<>();
    while (true) {
      List<Message> batch =
          sqs.receiveMessage(
                  request ->
                      request
                          .queueUrl(queue.toString())
                          .maxNumberOfMessages(10)
                          .waitTimeSeconds(1)
                          .messageSystemAttributeNames(MessageSystemAttributeName.ALL))
              .messages();
      if (batch.isEmpty()) {
        return all;
      }
      for (Message message : batch) {
        all.add(message);
        sqs.deleteMessage(
            request -> request.queueUrl(queue.toString()).receiptHandle(message.receiptHandle()));
      }
    }
  }

  private static OutboxEvent event(OutboxDestination destination, @Nullable String group, int n) {
    return new OutboxEvent(
        UUID.randomUUID(),
        destination,
        group,
        group == null ? null : (long) n,
        body(n),
        0,
        Instant.EPOCH);
  }

  private static String body(int n) {
    return "{\"n\":" + n + "}";
  }
}
