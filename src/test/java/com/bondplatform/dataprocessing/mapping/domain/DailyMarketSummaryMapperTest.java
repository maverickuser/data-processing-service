package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.Disposition;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class DailyMarketSummaryMapperTest {

  private static final ExchangeName BSE = ExchangeName.of("BSE");
  private static final DailyMarketSummaryMapper MAPPER =
      new DailyMarketSummaryMapper(BhavcopyContract.MAPPING);

  /** U-MAP-01. */
  @Test
  void mapsAcceptedRowWithManifestDateAndExchange() {
    CanonicalRow row = golden(2);

    DailyMarketSummary summary = MAPPER.map(GoldenBhavcopy.RUN, BSE, row).orElseThrow();

    assertThat(summary.isin()).isEqualTo(new Isin("INE001A07AB1"));
    assertThat(summary.tradeDate()).isEqualTo(new TradeDate(LocalDate.of(2026, 1, 1)));
    assertThat(summary.exchangeName()).isEqualTo(BSE);
    assertThat(summary.securityCode()).isEqualTo("973812");
    assertThat(summary.openPrice()).isEqualByComparingTo("1000.50");
    assertThat(summary.highPrice()).isEqualByComparingTo("1001");
    assertThat(summary.lowPrice()).isEqualByComparingTo("999");
    assertThat(summary.closePrice()).isEqualByComparingTo("1000.25");
    assertThat(summary.tradedVolume()).isEqualTo(BigInteger.valueOf(1200));
    assertThat(summary.numberOfTrades()).isEqualTo(BigInteger.valueOf(3));
    assertThat(summary.turnover()).isEqualByComparingTo("1200300");
    assertThat(summary.faceValue()).isEqualByComparingTo("1000");
    assertThat(summary.source())
        .isEqualTo(new SourceReference(GoldenBhavcopy.RUN.jobId(), "BSE_fgroup01012026.csv", "2"));
  }

  @Test
  void blankValuesMapToNull() {
    DailyMarketSummary summary = MAPPER.map(GoldenBhavcopy.RUN, BSE, golden(5)).orElseThrow();

    assertThat(summary.securityCode()).isEqualTo("000815");
    assertThat(summary.openPrice()).isNull();
    assertThat(summary.highPrice()).isNull();
    assertThat(summary.lowPrice()).isNull();
    assertThat(summary.closePrice()).isNull();
    assertThat(summary.tradedVolume()).isEqualTo(BigInteger.ZERO);
    assertThat(summary.turnover()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  /** U-MAP-02. */
  @Test
  void mapsOnlyAcceptedRows() {
    List<Long> mapped =
        GoldenBhavcopy.rows().stream()
            .filter(row -> MAPPER.map(GoldenBhavcopy.RUN, BSE, row).isPresent())
            .map(row -> row.record().recordNumber())
            .toList();

    assertThat(mapped).containsExactly(2L, 5L, 6L);
    assertThat(MAPPER.map(GoldenBhavcopy.RUN, BSE, golden(3))).isEqualTo(Optional.empty());
  }

  @Test
  void fieldsTheContractDoesNotMapStayNull() {
    MappingContract isinOnly =
        contract(InternalModel.DAILY_MARKET_SUMMARIES, Map.of("isin", "isin"));

    DailyMarketSummary summary =
        new DailyMarketSummaryMapper(isinOnly)
            .map(GoldenBhavcopy.RUN, BSE, golden(2))
            .orElseThrow();

    assertThat(summary.isin()).isEqualTo(new Isin("INE001A07AB1"));
    assertThat(summary.securityCode()).isNull();
    assertThat(summary.faceValue()).isNull();
  }

  @Test
  void rejectsContractForAnotherTarget() {
    assertThatThrownBy(
            () ->
                new DailyMarketSummaryMapper(
                    contract(InternalModel.SECURITIES, Map.of("isin", "isin"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not target daily market summaries");
  }

  @Test
  void readsEveryFieldTheInternalModelAllows() {
    assertThat(DailyMarketSummaryMapper.FIELDS)
        .isEqualTo(
            InternalModel.target(InternalModel.DAILY_MARKET_SUMMARIES)
                .orElseThrow()
                .fields()
                .keySet());
  }

  @Test
  void rejectsContractMappingToUnknownField() {
    assertThatThrownBy(
            () ->
                new DailyMarketSummaryMapper(
                    contract(
                        InternalModel.DAILY_MARKET_SUMMARIES,
                        Map.of("isin", "isin", "close_price", "closingPrice"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maps to unknown field closingPrice");
  }

  @Test
  void rejectsContractWithoutIsin() {
    assertThatThrownBy(
            () ->
                new DailyMarketSummaryMapper(
                    contract(
                        InternalModel.DAILY_MARKET_SUMMARIES,
                        Map.of("security_code", "securityCode"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not map the ISIN");
  }

  @Test
  void rejectsAcceptedRowWithoutIsin() {
    CanonicalRow invalid = golden(7);
    CanonicalRow forced =
        new CanonicalRow(invalid.record(), Disposition.ACCEPTED, OptionalLong.empty());

    assertThatThrownBy(() -> MAPPER.map(GoldenBhavcopy.RUN, BSE, forced))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Accepted record 7 has no ISIN");
  }

  private static CanonicalRow golden(long recordNumber) {
    return GoldenBhavcopy.rows().stream()
        .filter(row -> row.record().recordNumber() == recordNumber)
        .findFirst()
        .orElseThrow();
  }

  private static MappingContract contract(String target, Map<String, String> fields) {
    return new MappingContract(
        BhavcopyContract.MAPPING.id(),
        BhavcopyContract.MAPPING.sourceContract(),
        new MappingContract.RecordMapping(target, fields),
        List.of());
  }
}
