package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.shared.application.Metric;
import com.bondplatform.dataprocessing.shared.application.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OutboxSweeperHandlerTest {

  private final List<Duration> budgets = new ArrayList<>();
  private final Map<Metric, Long> recorded = new LinkedHashMap<>();
  private final Metrics metrics =
      (metric, value, dimensions) -> {
        assertThat(dimensions).isEmpty();
        recorded.put(metric, value);
      };
  private final OutboxSweeperHandler handler =
      new OutboxSweeperHandler(
          () -> 2,
          budget -> {
            budgets.add(budget);
            return 3;
          },
          () -> Duration.ofSeconds(90),
          metrics);

  @Test
  void sweepsWithTheDefaultBudgetWhenTheInvocationHasTimeToSpare() {
    String result = handler.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(59)));

    assertThat(result).isEqualTo("failed stuckJobs=2 delivered outboxEvents=3");
    assertThat(budgets).containsExactly(OutboxDispatcher.DEFAULT_SWEEP_BUDGET);
  }

  @Test
  void leavesRoomForTheLastSendWhenTimeIsShort() {
    handler.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(20)));

    assertThat(budgets).containsExactly(Duration.ofSeconds(5));
  }

  @ParameterizedTest
  @CsvSource({"16, 1", "44, 29", "45, 30", "46, 30", "60, 30"})
  void budgetIsTheRemainingTimeLessTheReserveAtMostTheDefault(long remainingSeconds, long budget) {
    handler.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(remainingSeconds)));

    assertThat(budgets).containsExactly(Duration.ofSeconds(budget));
  }

  @ParameterizedTest
  @ValueSource(longs = {15, 4})
  void sweepsNothingWhenNoTimeIsLeft(long remainingSeconds) {
    String result =
        handler.handleRequest(
            new ScheduledEvent(), remaining(Duration.ofSeconds(remainingSeconds)));

    assertThat(result).isEqualTo("failed stuckJobs=2 delivered outboxEvents=0");
    assertThat(budgets).isEmpty();
  }

  @Test
  void sweepsTheOutboxEvenWhenStuckJobsCannotBeFailed() {
    OutboxSweeperHandler failing =
        new OutboxSweeperHandler(
            () -> {
              throw new IllegalStateException("database unavailable");
            },
            budget -> 1,
            () -> Duration.ZERO,
            metrics);

    String result = failing.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(59)));

    assertThat(result).isEqualTo("failed stuckJobs=0 delivered outboxEvents=1");
    assertThat(recorded)
        .as("no stuck-job count is recorded when it is unknown")
        .containsExactly(Map.entry(Metric.OLDEST_PENDING_OUTBOX_AGE, 0L));
  }

  @Test
  void recordsStuckJobsFailedAndTheBacklogLeftAfterTheSweep() {
    handler.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(59)));

    assertThat(recorded)
        .containsExactly(
            Map.entry(Metric.STUCK_JOBS_FAILED, 2L),
            Map.entry(Metric.OLDEST_PENDING_OUTBOX_AGE, 90L));
  }

  @Test
  void backlogOrMetricFailureChangesNothingElse() {
    OutboxSweeperHandler unmeasured =
        new OutboxSweeperHandler(
            () -> 1,
            budget -> 1,
            () -> {
              throw new IllegalStateException("database unavailable");
            },
            (metric, value, dimensions) -> {
              throw new IllegalStateException("cannot write");
            });

    assertThat(unmeasured.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(59))))
        .isEqualTo("failed stuckJobs=1 delivered outboxEvents=1");
  }

  private static Context remaining(Duration time) {
    Context context = mock(Context.class);
    when(context.getRemainingTimeInMillis()).thenReturn(Math.toIntExact(time.toMillis()));
    return context;
  }
}
