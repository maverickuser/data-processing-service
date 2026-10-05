package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

/**
 * Checks what the repository sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code SecuritiesRepositoriesIT}.
 */
class JdbcSecurityRepositoryTest {

  private static final Instant RECORDED_AT = Instant.parse("2026-01-01T15:00:00Z");
  private static final Isin FIRST = Isin.of("INE0KH208019");
  private static final Isin SECOND = Isin.of("INE0K0Y08011");
  private static final SourceReference SOURCE =
      new SourceReference(
          JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10"),
          "INE0KH208019_coupon-details.json",
          "$.coupensVo.couponDetails.couponRate");

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
    assertThat(sent[0]).isEqualTo(RECORDED_AT.atOffset(ZoneOffset.UTC));
    assertThat(sent[1]).isEqualTo(RECORDED_AT.atOffset(ZoneOffset.UTC));
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

  @Test
  void lockReadsEveryStoredValueAndLeavesOutEmptyOnes() throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("issuer_name")).thenReturn("Acme Finance");
    when(row.getObject("allotment_date", LocalDate.class)).thenReturn(LocalDate.of(2019, 6, 10));
    when(row.getBigDecimal("original_face_value")).thenReturn(new BigDecimal("1000000.00"));
    when(row.getBigDecimal("coupon_rate_value")).thenReturn(new BigDecimal("8.94"));
    when(jdbc.query(eq(JdbcSecurityRepository.LOCK_VALUES), anyRowMapper(), eq(FIRST.value())))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(row, 0)));

    Map<String, SecurityValue> values = repository.lockValues(FIRST);

    assertThat(values)
        .containsExactly(
            Map.entry("issuerName", new SecurityValue.Text("Acme Finance")),
            Map.entry("allotmentDate", new SecurityValue.Date(LocalDate.of(2019, 6, 10))),
            Map.entry("originalFaceValue", new SecurityValue.Decimal(new BigDecimal("1000000"))),
            Map.entry("couponRate", new SecurityValue.Percentage(Percent.parse("8.94"))));
  }

  @Test
  void lockOfMissingSecurityFails() {
    when(jdbc.query(eq(JdbcSecurityRepository.LOCK_VALUES), anyRowMapper(), eq(FIRST.value())))
        .thenReturn(List.of());

    assertThatThrownBy(() -> repository.lockValues(FIRST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No security INE0KH208019");
  }

  @Test
  void lockStatementLocksTheRow() {
    assertThat(JdbcSecurityRepository.LOCK_VALUES)
        .startsWith("SELECT issuer_name, ")
        .contains("coupon_rate_value")
        .endsWith("FROM securities_data.securities WHERE isin = ? FOR UPDATE");
  }

  @Test
  void changesSetValuesUnitsAndSourcesAndClearOthers() {
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    SecurityScalars changes =
        new SecurityScalars(
            Map.of(
                "couponRate",
                new SecurityScalars.Field(
                    new SecurityValue.Percentage(Percent.parse("8.94")), SOURCE)),
            Set.of("assetCoverage"));

    repository.applyChanges(FIRST, changes, RECORDED_AT);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .update(
            eq(
                "UPDATE securities_data.securities SET coupon_rate_value = ?,"
                    + " coupon_rate_unit = ?, asset_coverage_value = NULL,"
                    + " asset_coverage_unit = NULL,"
                    + " field_sources = (field_sources - ?::text[]) || ?::jsonb,"
                    + " updated_at = ? WHERE isin = ?"),
            arguments.capture());
    Object[] sent = arguments.getValue();
    assertThat(sent[0]).isEqualTo(new BigDecimal("8.94"));
    assertThat(sent[1]).isEqualTo("PERCENT");
    assertThat((String[]) sent[2]).containsExactly("assetCoverage");
    assertThat(sent[3])
        .isEqualTo(
            "{\"couponRate\":{\"sourceFile\":\"INE0KH208019_coupon-details.json\","
                + "\"sourceLocation\":\"$.coupensVo.couponDetails.couponRate\","
                + "\"sourceRequestId\":\"0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10\"}}");
    assertThat(sent[4]).isEqualTo(RECORDED_AT.atOffset(ZoneOffset.UTC));
    assertThat(sent[5]).isEqualTo("INE0KH208019");
  }

  @Test
  void textDateAndDecimalAreSentAsTheyAre() {
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    Map<String, SecurityScalars.Field> fields = new LinkedHashMap<>();
    fields.put("couponType", new SecurityScalars.Field(new SecurityValue.Text("Simple"), SOURCE));
    fields.put(
        "redemptionDate",
        new SecurityScalars.Field(new SecurityValue.Date(LocalDate.of(2029, 6, 8)), SOURCE));
    fields.put(
        "originalFaceValue",
        new SecurityScalars.Field(new SecurityValue.Decimal(new BigDecimal("1000000.0")), SOURCE));

    repository.applyChanges(FIRST, new SecurityScalars(fields), RECORDED_AT);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(any(String.class), arguments.capture());
    assertThat(arguments.getValue())
        .startsWith("Simple", LocalDate.of(2029, 6, 8), new BigDecimal("1000000.0"));
  }

  @Test
  void noChangesTouchNothing() {
    repository.applyChanges(FIRST, new SecurityScalars(Map.of()), RECORDED_AT);

    verifyNoInteractions(jdbc);
  }

  @Test
  void changesToMissingSecurityOrUnknownFieldFail() {
    when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);
    SecurityScalars known =
        new SecurityScalars(
            Map.of(
                "couponType", new SecurityScalars.Field(new SecurityValue.Text("Simple"), SOURCE)));
    SecurityScalars unknown = new SecurityScalars(Map.of(), Set.of("maturity"));

    assertThatThrownBy(() -> repository.applyChanges(FIRST, known, RECORDED_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No security INE0KH208019");
    assertThatThrownBy(() -> repository.applyChanges(FIRST, unknown, RECORDED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A security has no field maturity");
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<Map<String, SecurityValue>> anyRowMapper() {
    return any(RowMapper.class);
  }
}
