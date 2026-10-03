package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse.BatchItemFailure;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.job.application.RunJob;
import com.bondplatform.dataprocessing.job.application.RunResult;
import com.bondplatform.dataprocessing.outbox.adapter.config.QueueProperties;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lambda entry point for the processing queue (LLD section 23.1): runs one attempt of the job each
 * message names.
 *
 * <p>The event source mapping uses batch size 1 and reports failures per message. A message is
 * reported as failed, so the queue delivers it again, only when the attempt failed temporarily or
 * could not be run at all. After a temporary failure the message's visibility is set to the retry
 * policy's delay; after the last attempt it is set to zero, so the queue's redrive policy moves it
 * to the dead-letter queue at once and the next job of its group can run (LLD section 23.3). A
 * message that can never succeed, because it is malformed or names no job, is acknowledged and
 * logged. The application context is started once, when Lambda initializes the function, and reused
 * by every invocation.
 */
public class ProcessingQueueHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessingQueueHandler.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Function<JobId, RunResult> runJob;
  private final MessageDelay messageDelay;

  /** Used by Lambda: starts the application context without a web server. */
  public ProcessingQueueHandler() {
    this(startApplication());
  }

  private ProcessingQueueHandler(ConfigurableApplicationContext application) {
    this(application.getBean(RunJob.class)::run, sqsDelay(application));
  }

  /**
   * Creates a handler that runs jobs with the given function and delays redeliveries with the given
   * action.
   */
  ProcessingQueueHandler(Function<JobId, RunResult> runJob, MessageDelay messageDelay) {
    this.runJob = runJob;
    this.messageDelay = messageDelay;
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<BatchItemFailure> failures = new ArrayList<>();
    for (SQSMessage message : event.getRecords()) {
      // On a FIFO queue, a message after a failed one must not run before it: once one fails,
      // it and every later message of the batch are reported as failed, unprocessed.
      if (!failures.isEmpty() || !isDone(message)) {
        failures.add(new BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  /** Runs the message's job; returns whether the message is done with. */
  private boolean isDone(SQSMessage message) {
    Optional<JobId> jobId = jobIdOf(message.getBody());
    if (jobId.isEmpty()) {
      LOG.error("Message {} names no job; it is dropped", message.getMessageId());
      return true;
    }
    RunResult result;
    try {
      result = runJob.apply(jobId.get());
    } catch (RuntimeException e) {
      // Outermost boundary of the worker: the queue delivers the message again.
      // A database error here can quote key values, so only the type and location are logged.
      LOG.error(
          "Job {} could not be run; the message will be delivered again, type={}, at={}",
          jobId.get(),
          e.getClass().getName(),
          e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
      return false;
    }
    if (!result.acknowledgesMessage()) {
      delayRedelivery(message, result);
    }
    return result.acknowledgesMessage();
  }

  /**
   * Makes the queue deliver the message again after the result's delay instead of after the
   * visibility timeout. If that fails, the message still comes back, only later.
   */
  private void delayRedelivery(SQSMessage message, RunResult result) {
    try {
      messageDelay.delay(message.getReceiptHandle(), result.retryDelay());
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not set the redelivery delay of message {}; it returns after the visibility"
              + " timeout",
          message.getMessageId(),
          e);
    }
  }

  /** Reads {@code {"jobId": "<uuid>"}}, the body the outbox sends for a queued job. */
  static Optional<JobId> jobIdOf(String body) {
    try {
      JsonNode jobId = JSON.readTree(body).path("jobId");
      return jobId.isString()
          ? Optional.of(new JobId(UUID.fromString(jobId.asString())))
          : Optional.empty();
    } catch (JacksonException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static ConfigurableApplicationContext startApplication() {
    return new SpringApplicationBuilder(DataProcessingApplication.class)
        .web(WebApplicationType.NONE)
        .run();
  }

  /** Delays redelivery by changing the message's visibility timeout on the job queue. */
  private static MessageDelay sqsDelay(ConfigurableApplicationContext application) {
    SqsClient sqs = application.getBean(SqsClient.class);
    String queueUrl = application.getBean(QueueProperties.class).fileProcessingUrl().toString();
    return (receiptHandle, delay) ->
        sqs.changeMessageVisibility(
            request ->
                request
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .visibilityTimeout(Math.toIntExact(delay.toSeconds())));
  }

  /** Sets how long the queue waits before delivering a message again. */
  @FunctionalInterface
  interface MessageDelay {

    /** Makes the message with this receipt handle visible again after the delay. */
    void delay(String receiptHandle, Duration delay);
  }
}
