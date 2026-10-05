package com.bondplatform.dataprocessing.operations.adapter.persistence;

import com.bondplatform.dataprocessing.operations.application.RetentionStore;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * Deletes expired rows from {@code data_processing}. Each statement deletes at most one batch and,
 * outside a caller's transaction, commits on its own.
 */
@Repository
public class JdbcRetentionStore implements RetentionStore {

  static final String DELETE_ISSUES =
      """
      DELETE FROM data_processing.validation_issues
      WHERE id IN (
        SELECT id FROM data_processing.validation_issues
        WHERE created_at < :cutoff
        LIMIT :limit)
      """;

  static final String DELETE_REJECTED_RECORDS =
      """
      DELETE FROM data_processing.rejected_records
      WHERE id IN (
        SELECT id FROM data_processing.rejected_records
        WHERE created_at < :cutoff
        LIMIT :limit)
      """;

  static final String DELETE_DELIVERED_EVENTS =
      """
      DELETE FROM data_processing.outbox_events
      WHERE id IN (
        SELECT id FROM data_processing.outbox_events
        WHERE status = 'DELIVERED' AND delivered_at < :cutoff
        LIMIT :limit)
      """;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates a store that runs its deletes through the given JDBC operations. */
  public JdbcRetentionStore(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int deleteIssuesCreatedBefore(Instant cutoff, int limit) {
    return delete(DELETE_ISSUES, cutoff, limit);
  }

  @Override
  public int deleteRejectedRecordsCreatedBefore(Instant cutoff, int limit) {
    return delete(DELETE_REJECTED_RECORDS, cutoff, limit);
  }

  @Override
  public int deleteDeliveredOutboxEventsBefore(Instant cutoff, int limit) {
    return delete(DELETE_DELIVERED_EVENTS, cutoff, limit);
  }

  private int delete(String sql, Instant cutoff, int limit) {
    return jdbc.update(
        sql,
        new MapSqlParameterSource()
            .addValue("cutoff", cutoff.atOffset(ZoneOffset.UTC))
            .addValue("limit", limit));
  }
}
