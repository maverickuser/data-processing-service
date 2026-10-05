package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SummaryPageTest {

  private static final Instant AT = Instant.parse("2026-01-01T15:00:00Z");
  private static final DailyMarketSummaryView SUMMARY =
      new DailyMarketSummaryView(
          "INE0KH208019",
          LocalDate.of(2026, 1, 1),
          "BSE",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          AT,
          AT);

  @Test
  void pageHoldsAtMostFifty() {
    assertThat(new SummaryPage(Collections.nCopies(50, SUMMARY), "next").items()).hasSize(50);
    List<DailyMarketSummaryView> tooMany = Collections.nCopies(51, SUMMARY);
    assertThatThrownBy(() -> new SummaryPage(tooMany, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("51 summaries do not fit on one page");
  }
}
