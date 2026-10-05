package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OutboxSweeperHandlerTest {

  private final List<Duration> budgets = new ArrayList<>();
  private final OutboxSweeperHandler handler =
      new OutboxSweeperHandler(
          () -> 2,
          budget -> {
            budgets.add(budget);
            return 3;
          });

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
            budget -> 1);

    String result = failing.handleRequest(new ScheduledEvent(), remaining(Duration.ofSeconds(59)));

    assertThat(result).isEqualTo("failed stuckJobs=0 delivered outboxEvents=1");
  }

  private static Context remaining(Duration time) {
    Context context = mock(Context.class);
    when(context.getRemainingTimeInMillis()).thenReturn(Math.toIntExact(time.toMillis()));
    return context;
  }
}
