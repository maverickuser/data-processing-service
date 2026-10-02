package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TradeDateTest {

  @Test
  void rejectsMissingValue() {
    assertThatNullPointerException().isThrownBy(() -> new TradeDate(nullValue()));
  }

  @SuppressWarnings("NullAway")
  private static LocalDate nullValue() {
    return null;
  }

  @Test
  void parsesIsoDate() {
    TradeDate tradeDate = TradeDate.parseIso("2026-01-01");

    assertThat(tradeDate.value()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(tradeDate).hasToString("2026-01-01");
  }

  @Test
  void acceptsLeapDay() {
    assertThat(TradeDate.parseIso("2028-02-29").value()).isEqualTo(LocalDate.of(2028, 2, 29));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-02-30",
        "2026-13-01",
        "01-01-2026",
        "2026/01/01",
        "20260101",
        "",
        "soon",
        "+12026-01-01",
        "-2026-01-01",
        "12026-01-01",
        "2026-1-1",
        " 2026-01-01"
      })
  void rejectsImpossibleOrDifferentlyFormattedDates(String text) {
    assertThatIllegalArgumentException().isThrownBy(() -> TradeDate.parseIso(text));
  }
}
