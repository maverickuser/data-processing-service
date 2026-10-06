package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.application.InvalidQueryException;
import com.bondplatform.dataprocessing.review.application.ListDailyMarketSummaries;
import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.review.domain.SummaryPage;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DailyMarketSummaryControllerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
  private static final Instant AT = Instant.parse("2026-01-01T15:00:00Z");
  private static final DailyMarketSummaryView SUMMARY =
      new DailyMarketSummaryView(
          "INE0KH208019", DAY, "BSE", null, null, null, null, null, null, null, null, null, AT, AT);

  private final ListDailyMarketSummaries list = mock(ListDailyMarketSummaries.class);
  private final DailyMarketSummaryController controller = new DailyMarketSummaryController(list);

  @Test
  void securityIsinIsTrimmedAndUppercasedAndPageIsShown() {
    when(list.forSecurity(Isin.of("INE0KH208019"), DAY, null, "t"))
        .thenReturn(Optional.of(new SummaryPage(List.of(SUMMARY), "next")));

    SummaryPageResponse response = controller.forSecurity(" ine0kh208019 ", DAY, null, "t");

    assertThat(response).isEqualTo(new SummaryPageResponse(List.of(SUMMARY), "next"));
  }

  @Test
  void unknownBlankOrControlIsinIsNotFound() {
    when(list.forSecurity(Isin.of("INE000000000"), null, null, null)).thenReturn(Optional.empty());

    for (String isin : List.of("INE000000000", "  ", "INE" + (char) 0, "I".repeat(65))) {
      assertThatThrownBy(() -> controller.forSecurity(isin, null, null, null))
          .isInstanceOfSatisfying(
              ApiProblemException.class,
              e -> assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND));
    }
  }

  @Test
  void invalidQueryIsBadRequest() {
    when(list.forSecurity(Isin.of("INE0KH208019"), DAY, DAY, "bad"))
        .thenThrow(new InvalidQueryException("The pageToken is not valid."));
    when(list.forTradeDate(DAY, " ", null))
        .thenThrow(new InvalidQueryException("The exchangeName filter must not be blank."));

    assertThatThrownBy(() -> controller.forSecurity("INE0KH208019", DAY, DAY, "bad"))
        .isInstanceOfSatisfying(
            ApiProblemException.class,
            e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_REQUEST));
    assertThatThrownBy(() -> controller.forTradeDate(DAY, " ", null))
        .isInstanceOfSatisfying(
            ApiProblemException.class,
            e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_REQUEST));
  }

  @Test
  void tradeDatePageIsShown() {
    when(list.forTradeDate(DAY, "BSE", null)).thenReturn(new SummaryPage(List.of(SUMMARY), null));

    assertThat(controller.forTradeDate(DAY, "BSE", null))
        .isEqualTo(new SummaryPageResponse(List.of(SUMMARY), null));
  }

  @Test
  void blankIsinNeverReachesTheUseCase() {
    assertThatThrownBy(() -> controller.forSecurity("", null, null, null))
        .isInstanceOf(ApiProblemException.class);
    verifyNoInteractions(list);
  }
}
