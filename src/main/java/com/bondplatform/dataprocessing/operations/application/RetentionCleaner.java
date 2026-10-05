package com.bondplatform.dataprocessing.operations.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.function.IntUnaryOperator;

/**
 * Deletes review data and delivered outbox events past their retention period (LLD section 21.1).
 *
 * <p>Rejected records and validation issues are kept for one year, delivered outbox events for 30
 * days. Undelivered events, jobs, runs, source files and business data are never deleted, so a job
 * still reports its stored counts and error count after cleanup; only its error list empties. Rows
 * are deleted in batches, each in its own transaction, so a large backlog never holds one long
 * transaction and a cleanup cut short keeps what it already deleted.
 */
public final class RetentionCleaner {

  /** How long rejected records and validation issues are kept. */
  static final Period REVIEW_DATA_RETENTION = Period.ofYears(1);

  /** How long an outbox event is kept after it was delivered. */
  static final Duration DELIVERED_EVENT_RETENTION = Duration.ofDays(30);

  /** Rows deleted per statement. */
  static final int BATCH_SIZE = 5_000;

  private final RetentionStore store;
  private final Clock clock;

  /** Creates a cleaner that deletes through the store, measuring age by the clock. */
  public RetentionCleaner(RetentionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Deletes everything past its retention period and returns how much was deleted. */
  public RetentionResult clean() {
    Instant now = clock.instant();
    Instant reviewCutoff = now.atOffset(ZoneOffset.UTC).minus(REVIEW_DATA_RETENTION).toInstant();
    Instant eventCutoff = now.minus(DELIVERED_EVENT_RETENTION);
    // Issues first, so deleting a record never has to cascade through a large set of them
    long issues = deleteAll(limit -> store.deleteIssuesCreatedBefore(reviewCutoff, limit));
    long records =
        deleteAll(limit -> store.deleteRejectedRecordsCreatedBefore(reviewCutoff, limit));
    long events = deleteAll(limit -> store.deleteDeliveredOutboxEventsBefore(eventCutoff, limit));
    return new RetentionResult(issues, records, events);
  }

  /** Repeats a batch delete until a batch comes back short. */
  private static long deleteAll(IntUnaryOperator batch) {
    long total = 0;
    int deleted;
    do {
      deleted = batch.applyAsInt(BATCH_SIZE);
      total += deleted;
    } while (deleted == BATCH_SIZE);
    return total;
  }
}
