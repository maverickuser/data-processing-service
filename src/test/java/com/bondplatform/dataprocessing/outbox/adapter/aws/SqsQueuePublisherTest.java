package com.bondplatform.dataprocessing.outbox.adapter.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.outbox.application.QueuePublishException;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsQueuePublisherTest {

  private static final UUID ID = UUID.fromString("bfed74ba-84ee-45b6-8a7a-50fbb53a0cbb");
  private static final URI FIFO =
      URI.create("https://sqs.ap-south-1.amazonaws.com/123456789012/file-processing.fifo");
  private static final URI STANDARD =
      URI.create("https://sqs.ap-south-1.amazonaws.com/210987654321/security-details");

  private final SqsClient sqs = mock(SqsClient.class);
  private final SqsQueuePublisher publisher =
      new SqsQueuePublisher(
          sqs,
          Map.of(
              OutboxDestination.FILE_PROCESSING,
              FIFO,
              OutboxDestination.SECURITY_DETAILS,
              STANDARD));

  @Test
  void fifoQueueGetsTheGroupAndTheEventIdAsDeduplicationId() {
    publisher.publish(event(OutboxDestination.FILE_PROCESSING, "trade-date:2026-09-21"));

    SendMessageRequest sent = sent();
    assertThat(sent.queueUrl()).isEqualTo(FIFO.toString());
    assertThat(sent.messageBody()).isEqualTo("{\"jobId\":\"j\"}");
    assertThat(sent.messageGroupId()).isEqualTo("trade-date:2026-09-21");
    assertThat(sent.messageDeduplicationId()).isEqualTo(ID.toString());
  }

  @Test
  void eventWithoutGroupSentToFifoQueueIsItsOwnGroup() {
    SqsQueuePublisher allFifo =
        new SqsQueuePublisher(
            sqs,
            Map.of(
                OutboxDestination.FILE_PROCESSING, FIFO, OutboxDestination.SECURITY_DETAILS, FIFO));

    allFifo.publish(event(OutboxDestination.SECURITY_DETAILS, null));

    assertThat(sent().messageGroupId()).isEqualTo(ID.toString());
  }

  @Test
  void standardQueueGetsTheBodyAlone() {
    publisher.publish(event(OutboxDestination.SECURITY_DETAILS, null));

    SendMessageRequest sent = sent();
    assertThat(sent.queueUrl()).isEqualTo(STANDARD.toString());
    assertThat(sent.messageGroupId()).isNull();
    assertThat(sent.messageDeduplicationId()).isNull();
  }

  @Test
  void refusalByAwsIsPublishFailureNamingItsCodeButNotTheQueue() {
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenThrow(
            SqsException.builder()
                .message("Access to https://sqs.../123456789012/file-processing.fifo denied")
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                .build());

    assertThatThrownBy(
            () -> publisher.publish(event(OutboxDestination.FILE_PROCESSING, "isin:INE121A07QY9")))
        .isInstanceOf(QueuePublishException.class)
        .hasMessage("Sending to FILE_PROCESSING failed: SqsException AccessDenied (HTTP 403)")
        .hasMessageNotContaining("123456789012");
  }

  @Test
  void failureBeforeReachingAwsIsPublishFailureToo() {
    when(sqs.sendMessage(any(SendMessageRequest.class)))
        .thenThrow(SdkClientException.create("Unable to execute HTTP request: 10.0.0.1"));

    assertThatThrownBy(() -> publisher.publish(event(OutboxDestination.SECURITY_DETAILS, null)))
        .isInstanceOf(QueuePublishException.class)
        .hasMessage("Sending to SECURITY_DETAILS failed: SdkClientException");
  }

  @Test
  void everyDestinationNeedsQueue() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new SqsQueuePublisher(sqs, Map.of(OutboxDestination.FILE_PROCESSING, FIFO)))
        .withMessageContaining("SECURITY_DETAILS");
  }

  private SendMessageRequest sent() {
    ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(sqs).sendMessage(request.capture());
    return request.getValue();
  }

  private static OutboxEvent event(OutboxDestination destination, @Nullable String group) {
    return new OutboxEvent(
        ID, destination, group, group == null ? null : 1L, "{\"jobId\":\"j\"}", 0, Instant.EPOCH);
  }
}
