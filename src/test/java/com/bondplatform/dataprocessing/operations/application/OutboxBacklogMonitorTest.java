package com.bondplatform.dataprocessing.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxBacklogMonitorTest {

  private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void ageIsHowLongTheOldestPendingEventHasWaited() {
    OutboxBacklogMonitor monitor =
        new OutboxBacklogMonitor(() -> Optional.of(NOW.minusSeconds(754)), CLOCK);

    assertThat(monitor.oldestPendingAge()).isEqualTo(Duration.ofSeconds(754));
  }

  @Test
  void ageIsZeroWhenNothingIsPending() {
    assertThat(new OutboxBacklogMonitor(Optional::empty, CLOCK).oldestPendingAge()).isZero();
  }

  @Test
  void eventStampedAfterNowByAnotherClockCountsAsZero() {
    OutboxBacklogMonitor monitor =
        new OutboxBacklogMonitor(() -> Optional.of(NOW.plusSeconds(2)), CLOCK);

    assertThat(monitor.oldestPendingAge()).isZero();
  }
}
