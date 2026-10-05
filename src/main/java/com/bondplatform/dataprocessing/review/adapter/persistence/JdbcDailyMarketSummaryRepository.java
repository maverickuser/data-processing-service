package com.bondplatform.dataprocessing.review.adapter.persistence;

import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * Reads daily market summaries from {@code securities_data}, keyset-paged. Only the columns the API
 * shows are selected: never source references.
 */
@Repository
public class JdbcDailyMarketSummaryRepository implements DailyMarketSummaryRepository {

  static final String EXISTS =
      "SELECT EXISTS (SELECT 1 FROM securities_data.securities WHERE isin = :isin)";

  static final String FOR_SECURITY =
      """
      SELECT isin, trade_date, exchange_name, security_code, open_price, high_price, low_price,
             close_price, traded_volume, number_of_trades, turnover, face_value, created_at,
             updated_at
      FROM securities_data.security_daily_market_summaries
      WHERE isin = :isin
        AND (CAST(:fromDate AS DATE) IS NULL OR trade_date >= CAST(:fromDate AS DATE))
        AND (CAST(:toDate AS DATE) IS NULL OR trade_date <= CAST(:toDate AS DATE))
        AND (CAST(:afterDate AS DATE) IS NULL
             OR trade_date < CAST(:afterDate AS DATE)
             OR (trade_date = CAST(:afterDate AS DATE)
                 AND exchange_name > CAST(:afterExchange AS TEXT)))
      ORDER BY trade_date DESC, exchange_name
      LIMIT :limit
      """;

  static final String FOR_TRADE_DATE =
      """
      SELECT isin, trade_date, exchange_name, security_code, open_price, high_price, low_price,
             close_price, traded_volume, number_of_trades, turnover, face_value, created_at,
             updated_at
      FROM securities_data.security_daily_market_summaries
      WHERE trade_date = :tradeDate
        AND (CAST(:exchangeName AS TEXT) IS NULL OR exchange_name = CAST(:exchangeName AS TEXT))
        AND (CAST(:afterIsin AS TEXT) IS NULL
             OR (isin, exchange_name)
                > (CAST(:afterIsin AS TEXT), CAST(:afterExchange AS TEXT)))
      ORDER BY isin, exchange_name
      LIMIT :limit
      """;

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcDailyMarketSummaryRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean securityExists(Isin isin) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            EXISTS, new MapSqlParameterSource("isin", isin.value()), Boolean.class));
  }

  @Override
  public List<DailyMarketSummaryView> forSecurity(
      Isin isin,
      @Nullable LocalDate fromDate,
      @Nullable LocalDate toDate,
      @Nullable SecurityPosition after,
      int limit) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource("isin", isin.value())
            .addValue("fromDate", fromDate, Types.DATE)
            .addValue("toDate", toDate, Types.DATE)
            .addValue("afterDate", after == null ? null : after.tradeDate(), Types.DATE)
            .addValue("afterExchange", after == null ? null : after.exchangeName(), Types.VARCHAR)
            .addValue("limit", limit);
    return jdbc.query(FOR_SECURITY, parameters, JdbcDailyMarketSummaryRepository::summary);
  }

  @Override
  public List<DailyMarketSummaryView> forTradeDate(
      LocalDate tradeDate,
      @Nullable String exchangeName,
      @Nullable TradeDatePosition after,
      int limit) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("tradeDate", tradeDate, Types.DATE)
            .addValue("exchangeName", exchangeName, Types.VARCHAR)
            .addValue("afterIsin", after == null ? null : after.isin(), Types.VARCHAR)
            .addValue("afterExchange", after == null ? null : after.exchangeName(), Types.VARCHAR)
            .addValue("limit", limit);
    return jdbc.query(FOR_TRADE_DATE, parameters, JdbcDailyMarketSummaryRepository::summary);
  }

  private static DailyMarketSummaryView summary(ResultSet row, int rowNumber) throws SQLException {
    return new DailyMarketSummaryView(
        row.getString("isin"),
        Objects.requireNonNull(row.getObject("trade_date", LocalDate.class)),
        row.getString("exchange_name"),
        row.getString("security_code"),
        row.getBigDecimal("open_price"),
        row.getBigDecimal("high_price"),
        row.getBigDecimal("low_price"),
        row.getBigDecimal("close_price"),
        integer(row.getBigDecimal("traded_volume")),
        integer(row.getBigDecimal("number_of_trades")),
        row.getBigDecimal("turnover"),
        row.getBigDecimal("face_value"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // The table's checks keep these whole, though NUMERIC may carry a zero fraction such as 14.0
  private static @Nullable BigInteger integer(@Nullable BigDecimal value) {
    return value == null ? null : value.toBigIntegerExact();
  }
}
