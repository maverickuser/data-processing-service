package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import org.junit.jupiter.api.Test;

class PublicationModelTest {

  private static final JobId REQUEST = JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");
  private static final SourceReference SOURCE =
      new SourceReference(REQUEST, "BSE_fgroup01012026.csv", "2");

  @Test
  void sourceReferenceNeedsFileAndLocation() {
    assertThat(SOURCE.location()).isEqualTo("2");
    assertThatIllegalArgumentException().isThrownBy(() -> new SourceReference(REQUEST, " ", "2"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SourceReference(REQUEST, "BSE_fgroup01012026.csv", ""));
    assertThatNullPointerException()
        .isThrownBy(() -> new SourceReference(missing(), "BSE_fgroup01012026.csv", "2"));
  }

  @Test
  void summaryMayHaveOnlyItsIdentityAndSource() {
    DailyMarketSummary summary =
        new DailyMarketSummary(
            Isin.of("INE0KH208019"),
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
            SOURCE);

    assertThat(summary.openPrice()).isNull();
    assertThat(summary.source()).isEqualTo(SOURCE);
  }

  @Test
  void summaryNeedsItsIdentityAndSource() {
    Isin isin = Isin.of("INE0KH208019");
    TradeDate date = TradeDate.parseIso("2026-01-01");
    ExchangeName exchange = ExchangeName.of("BSE");

    assertThatNullPointerException().isThrownBy(() -> summary(missing(), date, exchange, SOURCE));
    assertThatNullPointerException().isThrownBy(() -> summary(isin, missing(), exchange, SOURCE));
    assertThatNullPointerException().isThrownBy(() -> summary(isin, date, missing(), SOURCE));
    assertThatNullPointerException().isThrownBy(() -> summary(isin, date, exchange, missing()));
  }

  private static DailyMarketSummary summary(
      Isin isin, TradeDate date, ExchangeName exchange, SourceReference source) {
    return new DailyMarketSummary(
        isin, date, exchange, null, null, null, null, null, null, null, null, null, source);
  }

  @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
  private static <T> T missing() {
    return null;
  }
}
