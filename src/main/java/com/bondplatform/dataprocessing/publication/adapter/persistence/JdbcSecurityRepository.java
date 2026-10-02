package com.bondplatform.dataprocessing.publication.adapter.persistence;

import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Stores securities in {@code securities_data.securities}. */
@Repository
public class JdbcSecurityRepository implements SecurityRepository {

  /**
   * One statement for the whole set, with the ISINs passed as a single array parameter. They are
   * inserted in sorted order so that two concurrent transactions take their row locks in the same
   * order and cannot deadlock.
   */
  static final String INSERT_MISSING =
      """
      INSERT INTO securities_data.securities (isin, created_at, updated_at)
      SELECT new_security.isin, ?, ?
      FROM unnest(?::text[]) AS new_security (isin)
      ORDER BY new_security.isin
      ON CONFLICT (isin) DO NOTHING
      RETURNING isin
      """;

  private final JdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcSecurityRepository(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Set<Isin> insertMissing(Collection<Isin> isins, Instant recordedAt) {
    if (isins.isEmpty()) {
      return Set.of();
    }
    String[] values = isins.stream().map(Isin::value).distinct().toArray(String[]::new);
    Timestamp timestamp = Timestamp.from(recordedAt);
    return jdbc.queryForList(INSERT_MISSING, String.class, timestamp, timestamp, values).stream()
        .map(Isin::new)
        .collect(Collectors.toUnmodifiableSet());
  }
}
