package com.bondplatform.dataprocessing.operations.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Measures the age of the oldest pending outbox event, for the {@code OldestPendingOutboxAge}
 * metric (LLD section 23.8). An event that stays pending means a destination queue cannot be
 * reached, or that an older event of its group is holding it back.
 */
public final class OutboxBacklogMonitor {

  private final OutboxBacklog backlog;
  private final Clock clock;

  /** Creates a monitor of the given backlog. */
  public OutboxBacklogMonitor(OutboxBacklog backlog, Clock clock) {
    this.backlog = backlog;
    this.clock = clock;
  }

  /** Returns how long the oldest pending event has waited; zero when nothing is pending. */
  public Duration oldestPendingAge() {
    Instant now = clock.instant();
    return backlog
        .oldestPendingCreatedAt()
        .map(created -> Duration.between(created, now))
        .filter(Duration::isPositive)
        .orElse(Duration.ZERO);
  }
}
