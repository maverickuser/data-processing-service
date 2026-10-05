package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.operations.application.OutboxBacklogMonitor;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.shared.application.Metric;
import com.bondplatform.dataprocessing.shared.application.Metrics;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
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
 * bad job cannot hold every pending event back. Each run records {@code StuckJobsFailed} and, after
 * the sweep, {@code OldestPendingOutboxAge} (LLD section 23.8); a metric that cannot be measured or
 * recorded is logged and skipped.
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
  private final Supplier<Duration> oldestPendingAge;
  private final Metrics metrics;

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
        application.getBean(OutboxDispatcher.class)::sweep,
        application.getBean(OutboxBacklogMonitor.class)::oldestPendingAge,
        application.getBean(Metrics.class));
  }

  /**
   * Creates a handler that fails stuck jobs with the first function, sweeps with the second given
   * its time budget, and measures the backlog left with the third.
   */
  OutboxSweeperHandler(
      IntSupplier failStuckJobs,
      ToIntFunction<Duration> sweep,
      Supplier<Duration> oldestPendingAge,
      Metrics metrics) {
    this.failStuckJobs = failStuckJobs;
    this.sweep = sweep;
    this.oldestPendingAge = oldestPendingAge;
    this.metrics = metrics;
  }

  @Override
  public String handleRequest(ScheduledEvent event, Context context) {
    OptionalInt stuck = failStuckJobs();
    stuck.ifPresent(count -> record(Metric.STUCK_JOBS_FAILED, count));
    int failed = stuck.orElse(0);
    Duration budget = budget(context);
    int delivered = 0;
    if (budget.isPositive()) {
      delivered = sweep.applyAsInt(budget);
    } else {
      LOG.warn("Outbox sweep skipped: no time left in the invocation");
    }
    recordBacklog();
    LOG.info(
        "Sweep done, stuckJobsFailed={}, outboxEventsDelivered={}, budgetMillis={}",
        failed,
        delivered,
        budget.toMillis());
    return "failed stuckJobs=" + failed + " delivered outboxEvents=" + delivered;
  }

  /** Fails stuck jobs; returns how many, or empty when that could not be done. */
  private OptionalInt failStuckJobs() {
    try {
      return OptionalInt.of(failStuckJobs.getAsInt());
    } catch (RuntimeException e) {
      // A database error can quote key values, so only the type is logged.
      LOG.error(
          "Stuck jobs could not be failed; the outbox is swept anyway, type={}",
          e.getClass().getName());
      return OptionalInt.empty();
    }
  }

  private void recordBacklog() {
    try {
      record(Metric.OLDEST_PENDING_OUTBOX_AGE, oldestPendingAge.get().toSeconds());
    } catch (RuntimeException e) {
      LOG.warn("Outbox backlog could not be measured, type={}", e.getClass().getName());
    }
  }

  private void record(Metric metric, long value) {
    try {
      metrics.record(metric, value, Map.of());
    } catch (RuntimeException e) {
      LOG.warn("Metric not recorded, metric={}, type={}", metric, e.getClass().getName());
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
