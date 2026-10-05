package com.bondplatform.dataprocessing.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class StuckJobFailerTest {

  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
  private static final JobId FIRST = new JobId(new UUID(0, 1));
  private static final JobId SECOND = new JobId(new UUID(0, 2));
  private static final StuckJobRule RULE =
      new StuckJobRule(
          Instant.parse("2026-10-05T11:00:00Z"), Instant.parse("2026-10-05T11:44:00Z"), 3);

  private final StuckJobStore store = mock(StuckJobStore.class);
  private final StuckJobFailer failer =
      new StuckJobFailer(
          store, TransactionOperations.withoutTransaction(), 3, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void looksForJobsWithoutProgressForAnHourOrWithFinalAttemptPastTheVisibilityTimeout() {
    when(store.findStuck(RULE, StuckJobFailer.BATCH_SIZE)).thenReturn(List.of());

    assertThat(failer.failStuckJobs()).isZero();

    verify(store).findStuck(RULE, 100);
  }

  @Test
  void failsEachStuckJobAndCountsOnlyThoseStillStuck() {
    when(store.findStuck(any(), eq(100))).thenReturn(List.of(FIRST, SECOND));
    when(store.failIfStuck(FIRST, RULE, "ATTEMPT_ABANDONED", NOW)).thenReturn(true);
    when(store.failIfStuck(SECOND, RULE, "ATTEMPT_ABANDONED", NOW)).thenReturn(false);

    assertThat(failer.failStuckJobs()).isEqualTo(1);

    verify(store).failIfStuck(FIRST, RULE, "ATTEMPT_ABANDONED", NOW);
    verify(store).failIfStuck(SECOND, RULE, "ATTEMPT_ABANDONED", NOW);
  }
}
