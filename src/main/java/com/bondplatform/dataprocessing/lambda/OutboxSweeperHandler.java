package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lambda entry point for the every-minute sweeper schedule (LLD sections 21, 23.3 and 23.4): fails
 * stuck jobs, then delivers outbox events still pending, group by group in order. The event's
 * contents are not used. A failure to fail stuck jobs is logged and does not stop the sweep, so one
 * bad job cannot hold every pending event back.
 *
 * <p>The sweep starts no send once its budget is spent. The budget is the invocation's remaining
 * time less room for one send's call timeout and the recording of its outcome, and never more than
 * {@link OutboxDispatcher#DEFAULT_SWEEP_BUDGET}, so every send made is recorded before Lambda stops
 * the invocation. What is left waits for the next minute's run.
 */
public class OutboxSweeperHandler implements RequestHandler<ScheduledEvent, String> {

  /**
   * Room left after the budget: one send's 10-second call timeout and its database write. Database
   * calls have no time bound of their own; the database is assumed to answer promptly.
   */
  static final Duration RESERVE = Duration.ofSeconds(15);

  private static final Logger LOG = LoggerFactory.getLogger(OutboxSweeperHandler.class);

  private final IntSupplier failStuckJobs;
  private final ToIntFunction<Duration> sweep;

  /** Used by Lambda: starts the application context without a web server. */
  public OutboxSweeperHandler() {
    this(
        new SpringApplicationBuilder(DataProcessingApplication.class)
            .web(WebApplicationType.NONE)
            .run());
  }

  private OutboxSweeperHandler(ConfigurableApplicationContext application) {
    this(
        application.getBean(StuckJobFailer.class)::failStuckJobs,
        application.getBean(OutboxDispatcher.class)::sweep);
  }

  /**
   * Creates a handler that fails stuck jobs with the first function and sweeps with the second,
   * given its time budget.
   */
  OutboxSweeperHandler(IntSupplier failStuckJobs, ToIntFunction<Duration> sweep) {
    this.failStuckJobs = failStuckJobs;
    this.sweep = sweep;
  }

  @Override
  public String handleRequest(ScheduledEvent event, Context context) {
    int failed = failStuckJobs();
    Duration budget = budget(context);
    int delivered = 0;
    if (budget.isPositive()) {
      delivered = sweep.applyAsInt(budget);
    } else {
      LOG.warn("Outbox sweep skipped: no time left in the invocation");
    }
    LOG.info(
        "Sweep done, stuckJobsFailed={}, outboxEventsDelivered={}, budgetMillis={}",
        failed,
        delivered,
        budget.toMillis());
    return "failed stuckJobs=" + failed + " delivered outboxEvents=" + delivered;
  }

  /** Fails stuck jobs; returns how many, or zero when that could not be done. */
  private int failStuckJobs() {
    try {
      return failStuckJobs.getAsInt();
    } catch (RuntimeException e) {
      // A database error can quote key values, so only the type is logged.
      LOG.error(
          "Stuck jobs could not be failed; the outbox is swept anyway, type={}",
          e.getClass().getName());
      return 0;
    }
  }

  /** Returns how long the sweep may start sends; zero when no time is left. */
  static Duration budget(Context context) {
    Duration available = Duration.ofMillis(context.getRemainingTimeInMillis()).minus(RESERVE);
    if (available.isNegative()) {
      return Duration.ZERO;
    }
    return available.compareTo(OutboxDispatcher.DEFAULT_SWEEP_BUDGET) < 0
        ? available
        : OutboxDispatcher.DEFAULT_SWEEP_BUDGET;
  }
}
