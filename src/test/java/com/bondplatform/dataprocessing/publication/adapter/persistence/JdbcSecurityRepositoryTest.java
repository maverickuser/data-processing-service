package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Checks what the repository sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code SecuritiesRepositoriesIT}.
 */
class JdbcSecurityRepositoryTest {

  private static final Instant RECORDED_AT = Instant.parse("2026-01-01T15:00:00Z");
  private static final Isin FIRST = Isin.of("INE0KH208019");
  private static final Isin SECOND = Isin.of("INE0K0Y08011");

  private final JdbcOperations jdbc = mock(JdbcOperations.class);
  private final JdbcSecurityRepository repository = new JdbcSecurityRepository(jdbc);

  @Test
  void sendsDistinctIsinsAsOneArrayWithTheRecordedTime() {
    when(jdbc.queryForList(any(String.class), eq(String.class), any(Object[].class)))
        .thenReturn(List.of());

    repository.insertMissing(List.of(FIRST, SECOND, FIRST), RECORDED_AT);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .queryForList(
            eq(JdbcSecurityRepository.INSERT_MISSING), eq(String.class), arguments.capture());
    Object[] sent = arguments.getValue();
    assertThat(sent).hasSize(3);
    assertThat(sent[0]).isEqualTo(Timestamp.from(RECORDED_AT));
    assertThat(sent[1]).isEqualTo(Timestamp.from(RECORDED_AT));
    assertThat((String[]) sent[2]).containsExactly("INE0KH208019", "INE0K0Y08011");
  }

  @Test
  void returnsTheIsinsTheDatabaseReportsAsInserted() {
    when(jdbc.queryForList(any(String.class), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("INE0K0Y08011"));

    Set<Isin> created = repository.insertMissing(List.of(FIRST, SECOND), RECORDED_AT);

    assertThat(created).containsExactly(SECOND);
  }

  @Test
  void doesNotTouchTheDatabaseWhenThereIsNothingToInsert() {
    assertThat(repository.insertMissing(List.of(), RECORDED_AT)).isEmpty();

    verifyNoInteractions(jdbc);
  }

  @Test
  void statementIgnoresExistingSecuritiesAndReturnsNewOnes() {
    assertThat(JdbcSecurityRepository.INSERT_MISSING)
        .contains("securities_data.securities")
        .contains("ON CONFLICT (isin) DO NOTHING")
        .contains("RETURNING isin")
        .contains("ORDER BY");
  }
}
