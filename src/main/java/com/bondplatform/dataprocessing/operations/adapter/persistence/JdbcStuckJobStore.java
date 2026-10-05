package com.bondplatform.dataprocessing.operations.adapter.persistence;

import com.bondplatform.dataprocessing.operations.application.StuckJobRule;
import com.bondplatform.dataprocessing.operations.application.StuckJobStore;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/** Finds and fails stuck jobs in {@code data_processing}. */
@Repository
public class JdbcStuckJobStore implements StuckJobStore {

  /**
   * An unfinished job {@code j} is stuck: its latest progress, the later of its submission and its
   * runs' starts and ends, is before the cutoff, or its final attempt is still recorded as running
   * since before the attempt cutoff. {@code GREATEST} ignores nulls, so a job without runs counts
   * from its submission and a running run from its start.
   */
  static final String STUCK =
      """
      j.status IN ('QUEUED', 'PROCESSING', 'RETRY_PENDING')
      AND (
        GREATEST(j.submitted_at, (
          SELECT max(GREATEST(r.started_at, r.completed_at))
          FROM data_processing.processing_runs r
          WHERE r.ingestion_request_id = j.id)) < :progressCutoff
        OR (j.status = 'PROCESSING' AND j.attempt_count >= :finalAttempt
          AND EXISTS (
            SELECT 1 FROM data_processing.processing_runs r
            WHERE r.ingestion_request_id = j.id AND r.attempt_number = j.attempt_count
              AND r.status = 'RUNNING' AND r.started_at < :attemptCutoff)))
      """;

  static final String FIND_STUCK =
      "SELECT j.id FROM data_processing.ingestion_requests j WHERE "
          + STUCK
          + " ORDER BY j.acceptance_sequence LIMIT :limit";

  /**
   * Locks an unfinished job unless a worker holds it. The stuck check follows as its own statement,
   * so it reads the job's runs as of after the lock: a run a worker committed while this waited for
   * the lock is seen (in one statement, the runs would be read from before the wait).
   */
  static final String LOCK_UNFINISHED =
      """
      SELECT id FROM data_processing.ingestion_requests
      WHERE id = :id AND status IN ('QUEUED', 'PROCESSING', 'RETRY_PENDING')
      FOR UPDATE SKIP LOCKED
      """;

  static final String IS_STUCK =
      "SELECT j.id FROM data_processing.ingestion_requests j WHERE j.id = :id AND " + STUCK;

  static final String END_RUNNING_RUNS =
      """
      UPDATE data_processing.processing_runs
      SET status = 'FAILED_TEMPORARY', failure_code = :code, completed_at = :now
      WHERE ingestion_request_id = :id AND status = 'RUNNING'
      """;

  static final String FAIL_JOB =
      """
      UPDATE data_processing.ingestion_requests
      SET status = 'FAILED', completed_at = :now
      WHERE id = :id
      """;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates a store that runs its statements through the given JDBC operations. */
  public JdbcStuckJobStore(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<JobId> findStuck(StuckJobRule rule, int limit) {
    return jdbc
        .queryForList(FIND_STUCK, parameters(rule).addValue("limit", limit), UUID.class)
        .stream()
        .map(JobId::new)
        .toList();
  }

  @Override
  public boolean failIfStuck(JobId id, StuckJobRule rule, String abandonedRunCode, Instant now) {
    MapSqlParameterSource job = parameters(rule).addValue("id", id.value());
    if (jdbc.queryForList(LOCK_UNFINISHED, job, UUID.class).isEmpty()
        || jdbc.queryForList(IS_STUCK, job, UUID.class).isEmpty()) {
      return false;
    }
    MapSqlParameterSource change =
        new MapSqlParameterSource()
            .addValue("id", id.value())
            .addValue("code", abandonedRunCode)
            .addValue("now", now.atOffset(ZoneOffset.UTC));
    jdbc.update(END_RUNNING_RUNS, change);
    jdbc.update(FAIL_JOB, change);
    return true;
  }

  private static MapSqlParameterSource parameters(StuckJobRule rule) {
    return new MapSqlParameterSource()
        .addValue("progressCutoff", rule.progressCutoff().atOffset(ZoneOffset.UTC))
        .addValue("attemptCutoff", rule.finalAttemptCutoff().atOffset(ZoneOffset.UTC))
        .addValue("finalAttempt", rule.finalAttempt());
  }
}
