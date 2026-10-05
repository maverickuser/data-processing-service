package com.bondplatform.dataprocessing.operations.application;

import java.time.Instant;

/**
 * Deletes rows past their retention period (LLD section 21.1). Each call deletes at most {@code
 * limit} rows in its own short transaction and returns how many it deleted.
 */
public interface RetentionStore {

  /** Deletes validation issues created before the cutoff. */
  int deleteIssuesCreatedBefore(Instant cutoff, int limit);

  /** Deletes rejected records created before the cutoff, with any issues still attached. */
  int deleteRejectedRecordsCreatedBefore(Instant cutoff, int limit);

  /** Deletes outbox events delivered before the cutoff; pending events are never deleted. */
  int deleteDeliveredOutboxEventsBefore(Instant cutoff, int limit);
}
