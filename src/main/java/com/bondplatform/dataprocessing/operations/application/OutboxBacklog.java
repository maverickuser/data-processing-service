package com.bondplatform.dataprocessing.operations.application;

import java.time.Instant;
import java.util.Optional;

/** How far behind outbox delivery is. */
public interface OutboxBacklog {

  /** Returns when the oldest outbox event still pending was created, or empty if none is. */
  Optional<Instant> oldestPendingCreatedAt();
}
