package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.Disposition;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Collects a run's quarantined rows and stores them in bounded batches, so memory does not grow
 * with the file (LLD section 10). Accepted rows are ignored. Issues are numbered from 1 in the
 * order rows are added.
 *
 * <p>Rows still held are stored by {@link #flush()}, which the caller must call once every row was
 * added.
 */
public final class RejectedRecordBuffer {

  /** Rows per stored batch. */
  static final int BATCH_SIZE = 500;

  private final RejectedRecordStore store;
  private final CanonicalRun run;
  private final UUID processingRunId;
  private final String isinField;
  private final List<RejectedRow> pending = new ArrayList<>();
  private long issueCount;

  /**
   * Creates a buffer for one run.
   *
   * @param isinField the canonical field holding the ISIN
   */
  public RejectedRecordBuffer(
      RejectedRecordStore store, CanonicalRun run, UUID processingRunId, String isinField) {
    this.store = store;
    this.run = run;
    this.processingRunId = processingRunId;
    this.isinField = isinField;
  }

  /** Adds a row; a quarantined row is stored at the latest when the batch fills. */
  public void add(CanonicalRow row) {
    if (row.disposition() == Disposition.ACCEPTED) {
      return;
    }
    RejectedRow rejected = RejectedRow.of(row, isinField, issueCount + 1);
    issueCount += rejected.issues().size();
    pending.add(rejected);
    if (pending.size() == BATCH_SIZE) {
      flush();
    }
  }

  /** Stores the rows still held. */
  public void flush() {
    if (pending.isEmpty()) {
      return;
    }
    store.saveAll(run, processingRunId, List.copyOf(pending));
    pending.clear();
  }

  /** Returns how many issues the added rows have. */
  public long issueCount() {
    return issueCount;
  }
}
