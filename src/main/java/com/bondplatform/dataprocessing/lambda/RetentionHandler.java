package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.operations.application.RetentionResult;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Lambda entry point for the daily retention schedule (LLD section 21.1): deletes data past its
 * retention period. The event's contents are not used. A failure is left to propagate, so the
 * invocation fails and the next day's run deletes what this one did not.
 */
public class RetentionHandler implements RequestHandler<ScheduledEvent, String> {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionHandler.class);

  private final Supplier<RetentionResult> cleanup;

  /** Used by Lambda: starts the application context without a web server. */
  public RetentionHandler() {
    this(
        new SpringApplicationBuilder(DataProcessingApplication.class)
                .web(WebApplicationType.NONE)
                .run()
                .getBean(RetentionCleaner.class)
            ::clean);
  }

  /** Creates a handler that runs the given cleanup. */
  RetentionHandler(Supplier<RetentionResult> cleanup) {
    this.cleanup = cleanup;
  }

  @Override
  public String handleRequest(ScheduledEvent event, Context context) {
    RetentionResult result = cleanup.get();
    LOG.info(
        "Retention cleanup done, validationIssues={}, rejectedRecords={}, outboxEvents={}",
        result.validationIssues(),
        result.rejectedRecords(),
        result.outboxEvents());
    return "deleted validationIssues=%d rejectedRecords=%d outboxEvents=%d"
        .formatted(result.validationIssues(), result.rejectedRecords(), result.outboxEvents());
  }
}
