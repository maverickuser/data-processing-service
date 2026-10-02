package com.bondplatform.dataprocessing.publication.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.publication.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The securities and daily-market-summary repositories against PostgreSQL. */
class SecuritiesRepositoriesIT extends PostgresIntegrationTest {

  private static final Isin KNOWN = Isin.of("INE0KH208019");
  private static final Isin OTHER = Isin.of("INE0K0Y08011");
  private static final TradeDate DATE = TradeDate.parseIso("2026-01-01");
  private static final ExchangeName BSE = ExchangeName.of("BSE");
  private static final Instant FIRST = Instant.parse("2026-01-01T15:00:00Z");
  private static final Instant LATER = Instant.parse("2026-01-02T15:00:00Z");
  private static final UUID REQUEST = UUID.fromString("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");

  @Autowired private SecurityRepository securities;
  @Autowired private DailyMarketSummaryRepository summaries;

  @Test
  void insertMissingCreatesIsinOnlySecuritiesAndReportsThem() {
    Set<Isin> created = securities.insertMissing(List.of(KNOWN, OTHER), FIRST);

    assertThat(created).containsExactlyInAnyOrder(KNOWN, OTHER);
    Map<String, Object> row =
        jdbc.sql("SELECT * FROM securities_data.securities WHERE isin = ?")
            .param(KNOWN.value())
            .query()
            .singleRow();
    assertThat(row.get("issuer_name")).isNull();
    assertThat(value(row, "field_sources").toString()).isEqualTo("{}");
    assertThat(instantOf(row, "created_at")).isEqualTo(FIRST);
    assertThat(instantOf(row, "updated_at")).isEqualTo(FIRST);
  }

  @Test
  void insertMissingReportsOnlySecuritiesThatDidNotExist() {
    securities.insertMissing(List.of(KNOWN), FIRST);

    Set<Isin> created = securities.insertMissing(List.of(KNOWN, OTHER, OTHER), LATER);

    assertThat(created).containsExactly(OTHER);
    Map<String, Object> existing =
        jdbc.sql("SELECT * FROM securities_data.securities WHERE isin = ?")
            .param(KNOWN.value())
            .query()
            .singleRow();
    assertThat(instantOf(existing, "created_at")).isEqualTo(FIRST);
  }

  @Test
  void insertMissingWithNothingToInsertReturnsNothing() {
    assertThat(securities.insertMissing(List.of(), FIRST)).isEmpty();
  }

  @Test
  void upsertInsertsSummaryWithExactValuesAndSource() {
    securities.insertMissing(List.of(KNOWN), FIRST);

    summaries.upsertAll(List.of(summary(KNOWN, "114200.00", 14, "100000.00", "2")), FIRST);

    Map<String, Object> row = storedSummary(KNOWN);
    assertThat(decimal(row, "open_price")).isEqualByComparingTo("114200.00");
    assertThat(decimal(row, "open_price").scale()).isEqualTo(2);
    assertThat(decimal(row, "traded_volume")).isEqualByComparingTo("14");
    assertThat(decimal(row, "face_value")).isEqualByComparingTo("100000.00");
    assertThat(row.get("security_code")).isEqualTo("976009");
    assertThat(row.get("source_request_id")).isEqualTo(REQUEST);
    assertThat(row.get("source_file")).isEqualTo("BSE_fgroup01012026.csv");
    assertThat(row.get("source_location")).isEqualTo("2");
    assertThat(instantOf(row, "created_at")).isEqualTo(FIRST);
    assertThat(instantOf(row, "updated_at")).isEqualTo(FIRST);
  }

  @Test
  void upsertReplacesEveryValueIncludingWithNull() {
    securities.insertMissing(List.of(KNOWN), FIRST);
    summaries.upsertAll(List.of(summary(KNOWN, "114200.00", 14, "100000.00", "2")), FIRST);

    summaries.upsertAll(List.of(summary(KNOWN, null, null, null, "7")), LATER);

    Map<String, Object> row = storedSummary(KNOWN);
    assertThat(row.get("open_price")).isNull();
    assertThat(row.get("traded_volume")).isNull();
    assertThat(row.get("face_value")).isNull();
    assertThat(row.get("source_location")).isEqualTo("7");
    assertThat(instantOf(row, "created_at")).isEqualTo(FIRST);
    assertThat(instantOf(row, "updated_at")).isEqualTo(LATER);
    assertThat(countSummaries()).isEqualTo(1);
  }

  @Test
  void upsertLeavesOtherSecuritiesAndDatesUntouched() {
    securities.insertMissing(List.of(KNOWN, OTHER), FIRST);
    summaries.upsertAll(
        List.of(summary(KNOWN, "100.00", 1, "1000", "2"), summary(OTHER, "200.00", 2, "1000", "3")),
        FIRST);

    summaries.upsertAll(List.of(summary(KNOWN, "150.00", 5, "1000", "2")), LATER);

    assertThat(decimal(storedSummary(OTHER), "open_price")).isEqualByComparingTo("200.00");
    assertThat(instantOf(storedSummary(OTHER), "updated_at")).isEqualTo(FIRST);
    assertThat(countSummaries()).isEqualTo(2);
  }

  @Test
  void upsertWritesMoreRowsThanOneBatch() {
    List<Isin> isins = new ArrayList<>();
    List<DailyMarketSummary> many = new ArrayList<>();
    for (int index = 0; index < 1_201; index++) {
      Isin isin = Isin.of("TEST%08d".formatted(index));
      isins.add(isin);
      many.add(summary(isin, "1.00", index, "1000", String.valueOf(index + 2)));
    }
    securities.insertMissing(isins, FIRST);

    summaries.upsertAll(many, FIRST);

    assertThat(countSummaries()).isEqualTo(1_201);
  }

  @Test
  void upsertForUnknownSecurityIsRefused() {
    assertThatThrownBy(
            () -> summaries.upsertAll(List.of(summary(KNOWN, "1.00", 1, "1000", "2")), FIRST))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static DailyMarketSummary summary(
      Isin isin,
      @Nullable String openPrice,
      @Nullable Integer tradedVolume,
      @Nullable String faceValue,
      String recordNumber) {
    return new DailyMarketSummary(
        isin,
        DATE,
        BSE,
        "976009",
        openPrice == null ? null : new BigDecimal(openPrice),
        null,
        null,
        null,
        tradedVolume == null ? null : BigInteger.valueOf(tradedVolume),
        null,
        null,
        faceValue == null ? null : new BigDecimal(faceValue),
        new SourceReference(REQUEST, "BSE_fgroup01012026.csv", recordNumber));
  }

  private Map<String, Object> storedSummary(Isin isin) {
    return jdbc.sql("SELECT * FROM securities_data.security_daily_market_summaries WHERE isin = ?")
        .param(isin.value())
        .query()
        .singleRow();
  }

  private long countSummaries() {
    return jdbc.sql("SELECT count(*) FROM securities_data.security_daily_market_summaries")
        .query(Long.class)
        .single();
  }

  private static Object value(Map<String, Object> row, String column) {
    return Objects.requireNonNull(row.get(column), column);
  }

  private static BigDecimal decimal(Map<String, Object> row, String column) {
    return (BigDecimal) value(row, column);
  }

  private static Instant instantOf(Map<String, Object> row, String column) {
    return ((Timestamp) value(row, column)).toInstant();
  }
}
