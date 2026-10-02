package com.bondplatform.dataprocessing.publication.adapter.persistence;

import com.bondplatform.dataprocessing.publication.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Stores daily market summaries in {@code securities_data.security_daily_market_summaries}. */
@Repository
public class JdbcDailyMarketSummaryRepository implements DailyMarketSummaryRepository {

  /** Rows per database round trip; the caller's transaction spans all of them. */
  static final int BATCH_SIZE = 500;

  static final String UPSERT =
      """
      INSERT INTO securities_data.security_daily_market_summaries
        (isin, trade_date, exchange_name, security_code, open_price, high_price, low_price,
         close_price, traded_volume, number_of_trades, turnover, face_value,
         source_request_id, source_file, source_location, created_at, updated_at)
      VALUES
        (:isin, :tradeDate, :exchangeName, :securityCode, :openPrice, :highPrice, :lowPrice,
         :closePrice, :tradedVolume, :numberOfTrades, :turnover, :faceValue,
         :sourceRequestId, :sourceFile, :sourceLocation, :recordedAt, :recordedAt)
      ON CONFLICT (isin, trade_date, exchange_name) DO UPDATE SET
        security_code = EXCLUDED.security_code,
        open_price = EXCLUDED.open_price,
        high_price = EXCLUDED.high_price,
        low_price = EXCLUDED.low_price,
        close_price = EXCLUDED.close_price,
        traded_volume = EXCLUDED.traded_volume,
        number_of_trades = EXCLUDED.number_of_trades,
        turnover = EXCLUDED.turnover,
        face_value = EXCLUDED.face_value,
        source_request_id = EXCLUDED.source_request_id,
        source_file = EXCLUDED.source_file,
        source_location = EXCLUDED.source_location,
        updated_at = EXCLUDED.updated_at
      """;

  /**
   * Rows are written in key order, so two transactions that touch the same rows lock them in the
   * same order and cannot deadlock. The sort is stable: summaries with the same key keep the
   * caller's order.
   */
  private static final Comparator<DailyMarketSummary> KEY_ORDER =
      Comparator.comparing((DailyMarketSummary summary) -> summary.isin().value())
          .thenComparing(summary -> summary.tradeDate().value())
          .thenComparing(summary -> summary.exchangeName().value());

  private final NamedParameterJdbcOperations jdbc;

  /** Creates the repository. */
  public JdbcDailyMarketSummaryRepository(NamedParameterJdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsertAll(Collection<DailyMarketSummary> summaries, Instant recordedAt) {
    OffsetDateTime timestamp = recordedAt.atOffset(ZoneOffset.UTC);
    List<DailyMarketSummary> ordered = summaries.stream().sorted(KEY_ORDER).toList();
    for (int start = 0; start < ordered.size(); start += BATCH_SIZE) {
      List<DailyMarketSummary> batch =
          ordered.subList(start, Math.min(start + BATCH_SIZE, ordered.size()));
      jdbc.batchUpdate(
          UPSERT,
          batch.stream()
              .map(summary -> parametersOf(summary, timestamp))
              .toArray(SqlParameterSource[]::new));
    }
  }

  private static SqlParameterSource parametersOf(
      DailyMarketSummary summary, OffsetDateTime recordedAt) {
    return new MapSqlParameterSource()
        .addValue("isin", summary.isin().value())
        .addValue("tradeDate", summary.tradeDate().value())
        .addValue("exchangeName", summary.exchangeName().value())
        .addValue("securityCode", summary.securityCode())
        .addValue("openPrice", summary.openPrice())
        .addValue("highPrice", summary.highPrice())
        .addValue("lowPrice", summary.lowPrice())
        .addValue("closePrice", summary.closePrice())
        .addValue("tradedVolume", decimalOf(summary.tradedVolume()))
        .addValue("numberOfTrades", decimalOf(summary.numberOfTrades()))
        .addValue("turnover", summary.turnover())
        .addValue("faceValue", summary.faceValue())
        .addValue("sourceRequestId", summary.source().jobId().value())
        .addValue("sourceFile", summary.source().sourceFile())
        .addValue("sourceLocation", summary.source().location())
        .addValue("recordedAt", recordedAt);
  }

  /** The count columns are NUMERIC, which JDBC writes from a decimal. */
  private static @Nullable BigDecimal decimalOf(@Nullable BigInteger count) {
    return count == null ? null : new BigDecimal(count);
  }
}
