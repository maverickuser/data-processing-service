package com.bondplatform.dataprocessing.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobStatusTest {

  @ParameterizedTest
  @CsvSource({
    "QUEUED, false",
    "PROCESSING, false",
    "RETRY_PENDING, false",
    "COMPLETED, true",
    "COMPLETED_WITH_ERRORS, true",
    "FAILED, true"
  })
  void onlyFinishedJobsAreTerminal(JobStatus status, boolean terminal) {
    assertThat(status.isTerminal()).isEqualTo(terminal);
  }

  @Test
  void orderingGroupIsKeyedByTradeDateOrIsin() {
    assertThat(OrderingGroup.forTradeDate(TradeDate.parseIso("2026-01-01")).value())
        .isEqualTo("trade-date:2026-01-01");
    assertThat(OrderingGroup.forIsin(Isin.of("INE831R08076"))).hasToString("isin:INE831R08076");
  }
}
