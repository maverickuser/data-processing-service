package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import org.junit.jupiter.api.Test;

/**
 * The worker's {@link SQSEvent} is read with the Lambda Java runtime's own deserializer, which
 * loads {@code org.joda.time.DateTime} for every event type of {@code aws-lambda-java-events}.
 * Without joda-time on the classpath every worker invocation failed before the handler ran.
 */
class ProcessingQueueEventDeserializationTest {

  private static final String EVENT =
      """
      {"Records": [{
        "messageId": "059f36b4-87a3-44ab-83d2-661975830a7d",
        "receiptHandle": "AQEBwJnKyrHigUMZj6rYigCgxlaS3SLy0a",
        "body": "{\\"jobId\\": \\"0b9f4c1e-5a43-4c58-9d0b-3a1f5d2e7c11\\"}",
        "attributes": {"ApproximateReceiveCount": "1", "MessageGroupId": "g"},
        "messageAttributes": {},
        "eventSource": "aws:sqs",
        "eventSourceARN": "arn:aws:sqs:ap-south-1:123456789012:file-processing.fifo",
        "awsRegion": "ap-south-1"
      }]}
      """;

  @Test
  void lambdaRuntimeDeserializesTheWorkerEvent() {
    SQSEvent event =
        LambdaEventSerializers.serializerFor(SQSEvent.class, getClass().getClassLoader())
            .fromJson(EVENT);

    assertThat(event.getRecords()).hasSize(1);
    assertThat(event.getRecords().getFirst().getMessageId())
        .isEqualTo("059f36b4-87a3-44ab-83d2-661975830a7d");
    assertThat(event.getRecords().getFirst().getBody()).contains("0b9f4c1e");
  }
}
