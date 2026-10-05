package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import java.time.Duration;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Lambda entry point for the every-minute sweeper schedule (LLD sections 21 and 23.4): delivers
 * outbox events still pending, group by group in order. The event's contents are not used.
 *
 * <p>The sweep starts no send once its budget is spent. The budget is the invocation's remaining
 * time less room for one send's call timeout and the recording of its outcome, and never more than
 * {@link OutboxDispatcher#DEFAULT_SWEEP_BUDGET}, so every send made is recorded before Lambda stops
 * the invocation. What is left waits for the next minute's run.
 */
public class OutboxSweeperHandler implements RequestHandler<ScheduledEvent, String> {

  /** Room left after the budget: one send's 10-second call timeout and its database write. */
  static final Duration RESERVE = Duration.ofSeconds(15);

  private static final Logger LOG = LoggerFactory.getLogger(OutboxSweeperHandler.class);

  private final ToIntFunction<Duration> sweep;

  /** Used by Lambda: starts the application context without a web server. */
  public OutboxSweeperHandler() {
    this(
        new SpringApplicationBuilder(DataProcessingApplication.class)
                .web(WebApplicationType.NONE)
                .run()
                .getBean(OutboxDispatcher.class)
            ::sweep);
  }

  /** Creates a handler that sweeps with the given function, given its time budget. */
  OutboxSweeperHandler(ToIntFunction<Duration> sweep) {
    this.sweep = sweep;
  }

  @Override
  public String handleRequest(ScheduledEvent event, Context context) {
    Duration budget = budget(context);
    int delivered = budget.isPositive() ? sweep.applyAsInt(budget) : 0;
    LOG.info("Outbox sweep done, delivered={}, budgetMillis={}", delivered, budget.toMillis());
    return "delivered outboxEvents=" + delivered;
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
