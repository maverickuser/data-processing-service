package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-VAL-03, the filename's half. */
class CsvFilenameTest {

  @Test
  void bhavcopyNameGivesExchangeAndTradeDate() {
    assertThat(CsvFilename.parse("BSE_fgroup01012026.csv"))
        .contains(new CsvFilename(new ExchangeName("BSE"), TradeDate.parseIso("2026-01-01")));
  }

  @Test
  void exchangeIsTextBeforeTheFirstUnderscoreAndDateTheDigitsBeforeTheExtension() {
    assertThat(CsvFilename.parse("bse_debt_2026_fgroup21092026.CSV"))
        .contains(new CsvFilename(new ExchangeName("BSE"), TradeDate.parseIso("2026-09-21")));
  }

  // Review finding N-1: a capital that lengthens when lowercased must not shift the exchange.
  @Test
  void exchangeIsCutFromTheNameAsWritten() {
    assertThat(CsvFilename.parse("İSE_fgroup01012026.csv"))
        .hasValueSatisfying(name -> assertThat(name.exchange().value()).isEqualTo("İSE"));
    assertThat(CsvFilename.parse("İİİİİİ_01012026.csv")).isPresent();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fgroup01012026.csv",
        "_fgroup01012026.csv",
        " _fgroup01012026.csv",
        "BSE_fgroup.csv",
        "BSE_fgroup0101202.csv",
        "BSE_fgroup32132026.csv",
        "BSE_fgroup01012026.json",
        "BSE_fgroup01012026.csv.zip"
      })
  void nameWithoutExchangeOrRealDateIsNotBhavcopyName(String fileName) {
    assertThat(CsvFilename.parse(fileName)).isEmpty();
  }
}
