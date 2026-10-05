package com.bondplatform.dataprocessing.operations.application;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.List;

/** Finds and fails jobs that stopped making progress (LLD section 23.3). */
public interface StuckJobStore {

  /**
   * Returns up to {@code limit} unfinished jobs that are stuck by either rule, oldest first.
   *
   * @see #failIfStuck
   */
  List<JobId> findStuck(StuckJobRule rule, int limit);

  /**
   * Fails the job if it is still stuck, ending any run still recorded as running with the given
   * code. Must run inside a transaction; a job another transaction holds is skipped, not waited
   * for.
   *
   * @return whether the job was failed
   */
  boolean failIfStuck(JobId id, StuckJobRule rule, String abandonedRunCode, Instant now);
}
