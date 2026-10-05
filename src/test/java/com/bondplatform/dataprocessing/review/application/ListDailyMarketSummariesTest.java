package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository.SecurityPosition;
import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository.TradeDatePosition;
import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import com.bondplatform.dataprocessing.review.domain.SummaryPage;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ListDailyMarketSummariesTest {

  private static final Isin ISIN = Isin.of("INE0KH208019");
  private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
  private static final LocalDate LATER = LocalDate.of(2026, 1, 31);
  private static final String SECURITY_SCOPE = "security-summaries\nINE0KH208019\n\n";
  private static final String DATE_SCOPE = "trade-date-summaries\n2026-01-01\n";

  private final DailyMarketSummaryRepository summaries = mock(DailyMarketSummaryRepository.class);
  private final ListDailyMarketSummaries list = new ListDailyMarketSummaries(summaries);

  @Test
  void securityPageOfFiftyOneShowsFiftyAndPointsAtTheLastShown() {
    when(summaries.securityExists(ISIN)).thenReturn(true);
    when(summaries.forSecurity(ISIN, null, null, null, 51)).thenReturn(byDate(51));

    SummaryPage page = list.forSecurity(ISIN, null, null, null).orElseThrow();

    assertThat(page.items()).hasSize(50);
    String token = Objects.requireNonNull(page.nextToken());
    assertThat(PageToken.decode(token, SECURITY_SCOPE))
        .contains(page.items().getLast().tradeDate() + "\nBSE");
  }

  @Test
  void securityNextPageStartsAfterTheTokenAndLastPageHasNoToken() {
    when(summaries.securityExists(ISIN)).thenReturn(true);
    SecurityPosition after = new SecurityPosition(DAY, "B\nSE");
    when(summaries.forSecurity(ISIN, DAY, LATER, after, 51)).thenReturn(byDate(3));
    String scope = "security-summaries\nINE0KH208019\n2026-01-01\n2026-01-31";
    String token = new PageToken(scope, "2026-01-01\nB\nSE").encode();

    SummaryPage page = list.forSecurity(ISIN, DAY, LATER, token).orElseThrow();

    assertThat(page.items()).hasSize(3);
    assertThat(page.nextToken()).isNull();
  }

  @Test
  void unknownSecurityIsEmptyAndKnownOneWithoutSummariesIsAnEmptyPage() {
    when(summaries.securityExists(ISIN)).thenReturn(false);
    assertThat(list.forSecurity(ISIN, null, null, null)).isEmpty();
    verify(summaries, never()).forSecurity(any(), any(), any(), any(), anyInt());

    when(summaries.securityExists(ISIN)).thenReturn(true);
    when(summaries.forSecurity(ISIN, null, null, null, 51)).thenReturn(List.of());
    assertThat(list.forSecurity(ISIN, null, null, null).orElseThrow())
        .isEqualTo(new SummaryPage(List.of(), null));
  }

  @Test
  void reversedDatesOrBadTokenAreRejectedBeforeReading() {
    assertThatThrownBy(() -> list.forSecurity(ISIN, LATER, DAY, null))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The fromDate must not be after the toDate.");
    String otherDates = new PageToken(SECURITY_SCOPE + "x", "2026-01-01\nBSE").encode();
    for (String token :
        List.of(
            "changed",
            otherDates,
            new PageToken(SECURITY_SCOPE, "no-split").encode(),
            new PageToken(SECURITY_SCOPE, "not-a-date\nBSE").encode())) {
      assertThatThrownBy(() -> list.forSecurity(ISIN, null, null, token))
          .isInstanceOf(InvalidQueryException.class)
          .hasMessage("The pageToken is not valid.");
    }
    verifyNoInteractions(summaries);
  }

  @Test
  void sameDayBoundsAreAllowed() {
    when(summaries.securityExists(ISIN)).thenReturn(true);
    when(summaries.forSecurity(ISIN, DAY, DAY, null, 51)).thenReturn(byDate(1));

    assertThat(list.forSecurity(ISIN, DAY, DAY, null).orElseThrow().items()).hasSize(1);
  }

  @Test
  void tradeDatePageIsFilteredByTrimmedExchangeAndPointsAtTheLastShown() {
    when(summaries.forTradeDate(DAY, "BSE", null, 51)).thenReturn(byIsin(51));

    SummaryPage page = list.forTradeDate(DAY, "  BSE ", null);

    assertThat(page.items()).hasSize(50);
    assertThat(PageToken.decode(Objects.requireNonNull(page.nextToken()), DATE_SCOPE + "BSE"))
        .contains("12\nINE000000049BSE");
  }

  @Test
  void tradeDateNextPageStartsAfterTheToken() {
    TradeDatePosition after = new TradeDatePosition("INE000000049", "BSE");
    when(summaries.forTradeDate(eq(DAY), isNull(), eq(after), eq(51))).thenReturn(byIsin(2));

    SummaryPage page =
        list.forTradeDate(DAY, null, new PageToken(DATE_SCOPE, "12\nINE000000049BSE").encode());

    assertThat(page.items()).hasSize(2);
    assertThat(page.nextToken()).isNull();
  }

  @Test
  void badFilterOrTokenForTradeDateIsRejectedBeforeReading() {
    assertThatThrownBy(() -> list.forTradeDate(DAY, " ", null))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The exchangeName filter must not be blank.");
    assertThatThrownBy(() -> list.forTradeDate(DAY, "BSE" + (char) 0, null))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The exchangeName filter must not hold control characters.");
    String unfiltered = new PageToken(DATE_SCOPE, "12\nINE000000049BSE").encode();
    List<String> badPositions =
        List.of("INE000000049", "x\nINE", "-1\nINE", "99\nINE", Integer.MAX_VALUE + "\nINE");
    List<String> tokens = new java.util.ArrayList<>(List.of(unfiltered));
    badPositions.forEach(position -> tokens.add(new PageToken(DATE_SCOPE, position).encode()));
    for (String token : tokens) {
      String filter = token.equals(unfiltered) ? "BSE" : null;
      assertThatThrownBy(() -> list.forTradeDate(DAY, filter, token))
          .isInstanceOf(InvalidQueryException.class)
          .hasMessage("The pageToken is not valid.");
    }
    verifyNoInteractions(summaries);
  }

  // AP-1: an ISIN holding a line break still pages from exactly where it ended
  @Test
  void isinWithLineBreakRoundTripsThroughTheToken() {
    List<DailyMarketSummaryView> listed = new java.util.ArrayList<>(byIsin(49));
    listed.add(summary("INE\n1", DAY));
    listed.add(summary("INE\n2", DAY));
    when(summaries.forTradeDate(DAY, null, null, 51)).thenReturn(listed);
    String token = Objects.requireNonNull(list.forTradeDate(DAY, null, null).nextToken());
    TradeDatePosition after = new TradeDatePosition("INE\n1", "BSE");
    when(summaries.forTradeDate(DAY, null, after, 51)).thenReturn(List.of());

    assertThat(list.forTradeDate(DAY, null, token).items()).isEmpty();
  }

  private static List<DailyMarketSummaryView> byDate(int count) {
    return IntStream.range(0, count)
        .mapToObj(n -> summary(ISIN.value(), LATER.minusDays(n)))
        .toList();
  }

  private static List<DailyMarketSummaryView> byIsin(int count) {
    return IntStream.range(0, count).mapToObj(n -> summary("INE%09d".formatted(n), DAY)).toList();
  }

  static DailyMarketSummaryView summary(String isin, LocalDate tradeDate) {
    Instant at = Instant.parse("2026-01-01T15:00:00Z");
    return new DailyMarketSummaryView(
        isin, tradeDate, "BSE", null, null, null, null, null, null, null, null, null, at, at);
  }
}
