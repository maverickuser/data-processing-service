package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import java.util.List;
import java.util.UUID;

/**
 * Where quarantined rows and their issues are kept for the error-review API; accepted rows are only
 * in the canonical file (LLD section 17.5).
 */
public interface RejectedRecordStore {

  /**
   * Stores one batch of a run's quarantined rows with their issues.
   *
   * @param run the run the rows come from
   * @param processingRunId the attempt's processing run
   * @param rows the rows, each with its issues numbered
   */
  void saveAll(CanonicalRun run, UUID processingRunId, List<RejectedRow> rows);
}
