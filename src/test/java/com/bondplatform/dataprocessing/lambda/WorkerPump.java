package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.bondplatform.dataprocessing.job.application.RunJob;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Feeds a job queue's messages to the worker one at a time, as Lambda's event source mapping would,
 * deleting each message the worker reports done.
 */
final class WorkerPump {

  private static final Duration TIME_LIMIT = Duration.ofSeconds(30);

  private final SqsClient sqs;
  private final String queueUrl;
  private final ProcessingQueueHandler worker;

  /** Creates a pump whose worker runs jobs with {@code runJob} and delays redeliveries in SQS. */
  WorkerPump(SqsClient sqs, String queueUrl, RunJob runJob) {
    this.sqs = sqs;
    this.queueUrl = queueUrl;
    this.worker =
        new ProcessingQueueHandler(
            runJob::run,
            (receiptHandle, delay) ->
                sqs.changeMessageVisibility(
                    request ->
                        request
                            .queueUrl(queueUrl)
                            .receiptHandle(receiptHandle)
                            .visibilityTimeout(Math.toIntExact(delay.toSeconds()))));
  }

  /** Polls the queue until the condition holds, failing the test after 30 seconds. */
  void until(BooleanSupplier done) {
    long deadline = System.nanoTime() + TIME_LIMIT.toNanos();
    while (!done.getAsBoolean()) {
      assertThat(System.nanoTime()).as("time limit reached").isLessThan(deadline);
      List<Message> messages =
          sqs.receiveMessage(
                  request -> request.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(1))
              .messages();
      for (Message message : messages) {
        SQSMessage record = new SQSMessage();
        record.setMessageId(message.messageId());
        record.setReceiptHandle(message.receiptHandle());
        record.setBody(message.body());
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(record));
        SQSBatchResponse response = worker.handleRequest(event, new FixedLambdaContext());
        if (response.getBatchItemFailures().isEmpty()) {
          sqs.deleteMessage(
              request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
      }
    }
  }
}
