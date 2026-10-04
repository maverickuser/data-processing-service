package com.bondplatform.dataprocessing.outbox.adapter.aws;

import com.bondplatform.dataprocessing.outbox.application.QueuePublishException;
import com.bondplatform.dataprocessing.outbox.application.QueuePublisher;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Sends outbox events to SQS.
 *
 * <p>A queue whose name ends in {@code .fifo} is a FIFO queue, as AWS requires. For one, the
 * event's group is the message group, or, for an event without a group, its own ID, and the event
 * ID is the deduplication ID: a repeated send within the queue's five-minute window is delivered
 * once. A standard queue receives no group or deduplication ID.
 *
 * <p>A security-details event is a complete CloudEvent, so it carries the string message attribute
 * {@code contentType=application/cloudevents+json}, this service's SQS convention for the envelope
 * (LLD section 14.2). File-processing messages are internal and carry no attribute.
 */
public class SqsQueuePublisher implements QueuePublisher {

  private static final String FIFO_SUFFIX = ".fifo";

  static final String CONTENT_TYPE = "contentType";
  static final String CLOUD_EVENT_JSON = "application/cloudevents+json";

  private final SqsClient sqs;
  private final Map<OutboxDestination, URI> queueUrls;

  /**
   * Creates the publisher.
   *
   * @param queueUrls the queue URL of every destination
   * @throws IllegalArgumentException if a destination has no queue
   */
  public SqsQueuePublisher(SqsClient sqs, Map<OutboxDestination, URI> queueUrls) {
    for (OutboxDestination destination : OutboxDestination.values()) {
      if (!queueUrls.containsKey(destination)) {
        throw new IllegalArgumentException("No queue URL for " + destination);
      }
    }
    this.sqs = sqs;
    this.queueUrls = Map.copyOf(queueUrls);
  }

  @Override
  public void publish(OutboxEvent event) {
    String queueUrl = String.valueOf(queueUrls.get(event.destination()));
    SendMessageRequest.Builder request =
        SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.payloadJson());
    if (event.destination() == OutboxDestination.SECURITY_DETAILS) {
      request.messageAttributes(
          Map.of(
              CONTENT_TYPE,
              MessageAttributeValue.builder()
                  .dataType("String")
                  .stringValue(CLOUD_EVENT_JSON)
                  .build()));
    }
    if (queueUrl.endsWith(FIFO_SUFFIX)) {
      String group = event.messageGroup();
      request
          .messageGroupId(group == null ? event.id().toString() : group)
          .messageDeduplicationId(event.id().toString());
    }
    try {
      sqs.sendMessage(request.build());
    } catch (SdkException e) {
      throw new QueuePublishException(failureOf(event.destination(), e), e);
    }
  }

  /**
   * Describes a failed send for the stored last error: the destination, the kind of failure, and,
   * from AWS, its error code. The SDK's own message is left out, because it can name the queue URL,
   * account, and request.
   */
  static String failureOf(OutboxDestination destination, SdkException failure) {
    String kind = failure.getClass().getSimpleName();
    if (failure instanceof AwsServiceException service && service.awsErrorDetails() != null) {
      return "Sending to %s failed: %s %s (HTTP %d)"
          .formatted(
              destination, kind, service.awsErrorDetails().errorCode(), service.statusCode());
    }
    return "Sending to %s failed: %s".formatted(destination, kind);
  }
}
