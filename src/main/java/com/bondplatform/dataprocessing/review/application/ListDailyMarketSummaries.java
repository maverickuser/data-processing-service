package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository.SecurityPosition;
import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import com.bondplatform.dataprocessing.review.domain.SummaryPage;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Answers the per-security daily-market-summary request (LLD section 20.2), {@value
 * SummaryPage#SIZE} per page with the same opaque cursor as the error list.
 */
public class ListDailyMarketSummaries {

  /** Every supported date lies in this range; the ISO format allows years far outside it. */
  private static final LocalDate EARLIEST_DATE = LocalDate.of(1900, 1, 1);

  private static final LocalDate LATEST_DATE = LocalDate.of(9999, 12, 31);

  private static final String INVALID_TOKEN = "The pageToken is not valid.";

  private final DailyMarketSummaryRepository summaries;

  /** Creates the use case. */
  public ListDailyMarketSummaries(DailyMarketSummaryRepository summaries) {
    this.summaries = summaries;
  }

  /**
   * Returns one page of the security's summaries, newest trade date first, or empty if no security
   * has the ISIN. Without dates, paging starts at the most recent and continues backwards.
   *
   * @param fromDate the inclusive lower bound, or {@code null}
   * @param toDate the inclusive upper bound, or {@code null}
   * @param pageToken the previous page's {@code nextToken}, or {@code null} for the first page
   * @throws InvalidQueryException if {@code fromDate} is after {@code toDate}, or the token was not
   *     issued for this ISIN and these dates; checked before the ISIN is looked up, so a malformed
   *     request about an unknown ISIN is still invalid (AP-4)
   */
  public Optional<SummaryPage> forSecurity(
      Isin isin,
      @Nullable LocalDate fromDate,
      @Nullable LocalDate toDate,
      @Nullable String pageToken) {
    requireSupported("fromDate", fromDate);
    requireSupported("toDate", toDate);
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      throw new InvalidQueryException("The fromDate must not be after the toDate.");
    }
    String scope = "security-summaries\n" + isin + "\n" + text(fromDate) + "\n" + text(toDate);
    SecurityPosition after = position(pageToken, scope, ListDailyMarketSummaries::securityPosition);
    if (!summaries.securityExists(isin)) {
      return Optional.empty();
    }
    List<DailyMarketSummaryView> listed =
        summaries.forSecurity(isin, fromDate, toDate, after, SummaryPage.SIZE + 1);
    return Optional.of(page(listed, scope, last -> last.tradeDate() + "\n" + last.exchangeName()));
  }

  private static SummaryPage page(
      List<DailyMarketSummaryView> listed,
      String scope,
      Function<DailyMarketSummaryView, String> position) {
    boolean more = listed.size() > SummaryPage.SIZE;
    List<DailyMarketSummaryView> shown = more ? listed.subList(0, SummaryPage.SIZE) : listed;
    String next = more ? new PageToken(scope, position.apply(shown.getLast())).encode() : null;
    return new SummaryPage(shown, next);
  }

  private static <T> @Nullable T position(
      @Nullable String pageToken, String scope, Function<String, Optional<T>> parser) {
    if (pageToken == null) {
      return null;
    }
    return PageToken.decode(pageToken, scope)
        .flatMap(parser)
        .orElseThrow(() -> new InvalidQueryException(INVALID_TOKEN));
  }

  // A date never holds a line break, so the exchange name follows the first one
  private static Optional<SecurityPosition> securityPosition(String position) {
    int split = position.indexOf('\n');
    if (split < 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new SecurityPosition(
              LocalDate.parse(position.substring(0, split)), position.substring(split + 1)));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String text(@Nullable LocalDate date) {
    return date == null ? "" : date.toString();
  }

  /** Refuses a date the database cannot store, rather than fail while querying with it. */
  private static void requireSupported(String name, @Nullable LocalDate date) {
    if (date != null && (date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE))) {
      throw new InvalidQueryException("The " + name + " must be between 1900 and 9999.");
    }
  }
}
