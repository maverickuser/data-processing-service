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
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lambda entry point for the processing queue (LLD section 23.1): runs one attempt of the job each
 * message names.
 *
 * <p>The event source mapping uses batch size 1 and reports failures per message. A message is
 * reported as failed, so the queue delivers it again, only when the attempt failed temporarily or
 * could not be run at all. A message that can never succeed, because it is malformed or names no
 * job, is acknowledged and logged. The application context is started once, when Lambda initializes
 * the function, and reused by every invocation.
 */
public class ProcessingQueueHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessingQueueHandler.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Function<JobId, RunResult> runJob;

  /** Used by Lambda: starts the application context without a web server. */
  public ProcessingQueueHandler() {
    this(startApplication()::run);
  }

  /** Creates a handler that runs jobs with the given function. */
  ProcessingQueueHandler(Function<JobId, RunResult> runJob) {
    this.runJob = runJob;
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    List<BatchItemFailure> failures = new ArrayList<>();
    for (SQSMessage message : event.getRecords()) {
      if (!isDone(message)) {
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
    try {
      return runJob.apply(jobId.get()).acknowledgesMessage();
    } catch (RuntimeException e) {
      // Outermost boundary of the worker: the queue delivers the message again.
      LOG.error("Job {} could not be run; the message will be delivered again", jobId.get(), e);
      return false;
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

  private static RunJob startApplication() {
    return new SpringApplicationBuilder(DataProcessingApplication.class)
        .web(WebApplicationType.NONE)
        .run()
        .getBean(RunJob.class);
  }
}
