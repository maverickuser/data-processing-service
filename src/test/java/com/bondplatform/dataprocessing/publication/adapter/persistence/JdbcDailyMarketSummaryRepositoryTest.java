package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the repository sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code SecuritiesRepositoriesIT}.
 */
class JdbcDailyMarketSummaryRepositoryTest {

  private static final Instant RECORDED_AT = Instant.parse("2026-01-01T15:00:00Z");
  private static final UUID REQUEST = UUID.fromString("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final JdbcDailyMarketSummaryRepository repository =
      new JdbcDailyMarketSummaryRepository(jdbc);

  @Test
  void bindsEveryColumnOfSummary() {
    DailyMarketSummary summary =
        new DailyMarketSummary(
            Isin.of("INE0KH208019"),
            TradeDate.parseIso("2026-01-01"),
            ExchangeName.of("BSE"),
            "976009",
            new BigDecimal("114200.00"),
            new BigDecimal("114300.00"),
            new BigDecimal("114100.00"),
            new BigDecimal("114250.00"),
            BigInteger.valueOf(14),
            BigInteger.ONE,
            new BigDecimal("1598800.00"),
            new BigDecimal("100000.00"),
            new SourceReference(REQUEST, "BSE_fgroup01012026.csv", "2"));

    repository.upsertAll(List.of(summary), RECORDED_AT);

    SqlParameterSource bound = singleBatch()[0];
    assertThat(bound.getValue("isin")).isEqualTo("INE0KH208019");
    assertThat(bound.getValue("tradeDate")).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(bound.getValue("exchangeName")).isEqualTo("BSE");
    assertThat(bound.getValue("securityCode")).isEqualTo("976009");
    assertThat(bound.getValue("openPrice")).isEqualTo(new BigDecimal("114200.00"));
    assertThat(bound.getValue("highPrice")).isEqualTo(new BigDecimal("114300.00"));
    assertThat(bound.getValue("lowPrice")).isEqualTo(new BigDecimal("114100.00"));
    assertThat(bound.getValue("closePrice")).isEqualTo(new BigDecimal("114250.00"));
    assertThat(bound.getValue("tradedVolume")).isEqualTo(new BigDecimal("14"));
    assertThat(bound.getValue("numberOfTrades")).isEqualTo(new BigDecimal("1"));
    assertThat(bound.getValue("turnover")).isEqualTo(new BigDecimal("1598800.00"));
    assertThat(bound.getValue("faceValue")).isEqualTo(new BigDecimal("100000.00"));
    assertThat(bound.getValue("sourceRequestId")).isEqualTo(REQUEST);
    assertThat(bound.getValue("sourceFile")).isEqualTo("BSE_fgroup01012026.csv");
    assertThat(bound.getValue("sourceLocation")).isEqualTo("2");
    assertThat(bound.getValue("recordedAt")).isEqualTo(Timestamp.from(RECORDED_AT));
  }

  @Test
  void bindsAbsentValuesAsNullSoTheyOverwrite() {
    repository.upsertAll(List.of(identityOnly(1)), RECORDED_AT);

    SqlParameterSource bound = singleBatch()[0];
    for (String optional :
        List.of(
            "securityCode",
            "openPrice",
            "highPrice",
            "lowPrice",
            "closePrice",
            "tradedVolume",
            "numberOfTrades",
            "turnover",
            "faceValue")) {
      assertThat(bound.hasValue(optional)).as(optional).isTrue();
      assertThat(bound.getValue(optional)).as(optional).isNull();
    }
  }

  @Test
  void writesInBatchesOfFiveHundred() {
    List<DailyMarketSummary> summaries = new ArrayList<>();
    for (int index = 0; index < 1_201; index++) {
      summaries.add(identityOnly(index));
    }

    repository.upsertAll(summaries, RECORDED_AT);

    ArgumentCaptor<SqlParameterSource[]> batches =
        ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc, times(3))
        .batchUpdate(eq(JdbcDailyMarketSummaryRepository.UPSERT), batches.capture());
    assertThat(batches.getAllValues())
        .extracting(batch -> batch.length)
        .containsExactly(500, 500, 201);
    assertThat(batches.getAllValues().get(2)[200].getValue("sourceLocation")).isEqualTo("1202");
  }

  @Test
  void doesNotTouchTheDatabaseWhenThereIsNothingToWrite() {
    repository.upsertAll(List.of(), RECORDED_AT);

    verifyNoInteractions(jdbc);
  }

  @Test
  void statementReplacesEveryValueButKeepsTheCreationTime() {
    String update =
        JdbcDailyMarketSummaryRepository.UPSERT.substring(
            JdbcDailyMarketSummaryRepository.UPSERT.indexOf("DO UPDATE SET"));

    assertThat(JdbcDailyMarketSummaryRepository.UPSERT)
        .contains("ON CONFLICT (isin, trade_date, exchange_name)");
    assertThat(update)
        .contains(
            "security_code = EXCLUDED.security_code",
            "open_price = EXCLUDED.open_price",
            "high_price = EXCLUDED.high_price",
            "low_price = EXCLUDED.low_price",
            "close_price = EXCLUDED.close_price",
            "traded_volume = EXCLUDED.traded_volume",
            "number_of_trades = EXCLUDED.number_of_trades",
            "turnover = EXCLUDED.turnover",
            "face_value = EXCLUDED.face_value",
            "source_request_id = EXCLUDED.source_request_id",
            "source_file = EXCLUDED.source_file",
            "source_location = EXCLUDED.source_location",
            "updated_at = EXCLUDED.updated_at")
        .doesNotContain("created_at");
  }

  private SqlParameterSource[] singleBatch() {
    ArgumentCaptor<SqlParameterSource[]> batch =
        ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc).batchUpdate(any(String.class), batch.capture());
    return batch.getValue();
  }

  private static DailyMarketSummary identityOnly(int index) {
    return new DailyMarketSummary(
        Isin.of("TEST%08d".formatted(index)),
        TradeDate.parseIso("2026-01-01"),
        ExchangeName.of("BSE"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new SourceReference(REQUEST, "BSE_fgroup01012026.csv", String.valueOf(index + 2)));
  }
}
