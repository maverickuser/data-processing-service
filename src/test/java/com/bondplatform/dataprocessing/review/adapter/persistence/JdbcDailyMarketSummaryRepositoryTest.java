package com.bondplatform.dataprocessing.review.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository.SecurityPosition;
import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository.TradeDatePosition;
import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcDailyMarketSummaryRepositoryTest {

  private static final Isin ISIN = Isin.of("INE0KH208019");
  private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
  private static final OffsetDateTime AT =
      OffsetDateTime.of(2026, 1, 1, 15, 0, 0, 0, ZoneOffset.UTC);

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcDailyMarketSummaryRepository repository =
      new JdbcDailyMarketSummaryRepository(jdbc);

  @Test
  void securityExistsAsksTheSecuritiesTable() {
    when(jdbc.queryForObject(
            eq(JdbcDailyMarketSummaryRepository.EXISTS),
            any(SqlParameterSource.class),
            eq(Boolean.class)))
        .thenReturn(true, false, null);

    assertThat(repository.securityExists(ISIN)).isTrue();
    assertThat(repository.securityExists(ISIN)).isFalse();
    assertThat(repository.securityExists(ISIN)).isFalse();
  }

  @Test
  void securityQueryBindsTypedBoundsAndPosition() {
    ArgumentCaptor<MapSqlParameterSource> parameters =
        queried(JdbcDailyMarketSummaryRepository.FOR_SECURITY);

    repository.forSecurity(ISIN, DAY, null, new SecurityPosition(DAY, "BSE"), 51);
    repository.forSecurity(ISIN, null, DAY, null, 51);

    MapSqlParameterSource after = parameters.getAllValues().get(0);
    assertThat(after.getValue("isin")).isEqualTo("INE0KH208019");
    assertThat(after.getValue("fromDate")).isEqualTo(DAY);
    assertThat(after.getSqlType("fromDate")).isEqualTo(Types.DATE);
    assertThat(after.getValue("toDate")).isNull();
    assertThat(after.getSqlType("toDate")).isEqualTo(Types.DATE);
    assertThat(after.getValue("afterDate")).isEqualTo(DAY);
    assertThat(after.getValue("afterExchange")).isEqualTo("BSE");
    assertThat(after.getValue("limit")).isEqualTo(51);
    MapSqlParameterSource first = parameters.getAllValues().get(1);
    assertThat(first.getValue("afterDate")).isNull();
    assertThat(first.getValue("afterExchange")).isNull();
    assertThat(JdbcDailyMarketSummaryRepository.FOR_SECURITY)
        .contains("ORDER BY trade_date DESC, exchange_name")
        .doesNotContain("source_");
  }

  @Test
  void tradeDateQueryBindsFilterAndPosition() {
    ArgumentCaptor<MapSqlParameterSource> parameters =
        queried(JdbcDailyMarketSummaryRepository.FOR_TRADE_DATE);

    repository.forTradeDate(DAY, "BSE", new TradeDatePosition("INE0KH208019", "BSE"), 51);
    repository.forTradeDate(DAY, null, null, 51);

    MapSqlParameterSource after = parameters.getAllValues().get(0);
    assertThat(after.getValue("tradeDate")).isEqualTo(DAY);
    assertThat(after.getValue("exchangeName")).isEqualTo("BSE");
    assertThat(after.getValue("afterIsin")).isEqualTo("INE0KH208019");
    assertThat(after.getValue("afterExchange")).isEqualTo("BSE");
    MapSqlParameterSource first = parameters.getAllValues().get(1);
    assertThat(first.getValue("exchangeName")).isNull();
    assertThat(first.getSqlType("exchangeName")).isEqualTo(Types.VARCHAR);
    assertThat(first.getValue("afterIsin")).isNull();
    assertThat(first.getValue("afterExchange")).isNull();
    assertThat(JdbcDailyMarketSummaryRepository.FOR_TRADE_DATE)
        .contains("ORDER BY isin, exchange_name")
        .doesNotContain("source_");
  }

  @Test
  void rowMapsEveryShownColumnAndWholeCountsKeepNoFraction() throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("isin")).thenReturn("INE0KH208019");
    when(row.getObject("trade_date", LocalDate.class)).thenReturn(DAY);
    when(row.getString("exchange_name")).thenReturn("BSE");
    when(row.getString("security_code")).thenReturn("976009");
    when(row.getBigDecimal("open_price")).thenReturn(new BigDecimal("114200.00"));
    when(row.getBigDecimal("high_price")).thenReturn(new BigDecimal("114200.01"));
    when(row.getBigDecimal("low_price")).thenReturn(new BigDecimal("114199.99"));
    when(row.getBigDecimal("close_price")).thenReturn(new BigDecimal("114200"));
    when(row.getBigDecimal("traded_volume")).thenReturn(new BigDecimal("14.0"));
    when(row.getBigDecimal("number_of_trades")).thenReturn(null);
    when(row.getBigDecimal("turnover")).thenReturn(new BigDecimal("1598800.00"));
    when(row.getBigDecimal("face_value")).thenReturn(new BigDecimal("100000.00"));
    when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(AT);
    when(row.getObject("updated_at", OffsetDateTime.class)).thenReturn(AT.plusHours(1));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<RowMapper<DailyMarketSummaryView>> mapper =
        ArgumentCaptor.forClass(RowMapper.class);
    when(jdbc.query(
            eq(JdbcDailyMarketSummaryRepository.FOR_TRADE_DATE),
            any(SqlParameterSource.class),
            mapper.capture()))
        .thenReturn(List.of());

    repository.forTradeDate(DAY, null, null, 51);
    DailyMarketSummaryView summary = Objects.requireNonNull(mapper.getValue().mapRow(row, 0));

    assertThat(summary)
        .isEqualTo(
            new DailyMarketSummaryView(
                "INE0KH208019",
                DAY,
                "BSE",
                "976009",
                new BigDecimal("114200.00"),
                new BigDecimal("114200.01"),
                new BigDecimal("114199.99"),
                new BigDecimal("114200"),
                BigInteger.valueOf(14),
                null,
                new BigDecimal("1598800.00"),
                new BigDecimal("100000.00"),
                AT.toInstant(),
                AT.plusHours(1).toInstant()));
  }

  private ArgumentCaptor<MapSqlParameterSource> queried(String sql) {
    ArgumentCaptor<MapSqlParameterSource> parameters =
        ArgumentCaptor.forClass(MapSqlParameterSource.class);
    when(jdbc.query(eq(sql), parameters.capture(), any(RowMapper.class))).thenReturn(List.of());
    return parameters;
  }
}
