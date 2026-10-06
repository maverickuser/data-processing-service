package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.application.InvalidQueryException;
import com.bondplatform.dataprocessing.review.application.ListDailyMarketSummaries;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves daily market summaries by security and by trade date (LLD section 20.2). */
@RestController
@ConditionalOnBooleanProperty(name = "data-processing.api.read-routes", matchIfMissing = true)
public class DailyMarketSummaryController {

  private final ListDailyMarketSummaries listSummaries;

  /** Creates the controller. */
  public DailyMarketSummaryController(ListDailyMarketSummaries listSummaries) {
    this.listSummaries = listSummaries;
  }

  /**
   * Returns one page of the security's summaries, newest trade date first. The ISIN is trimmed and
   * uppercased first.
   *
   * @throws ApiProblemException {@code 404} if no security has the ISIN, {@code 400} if the dates
   *     are reversed or the token is not valid
   */
  @GetMapping(
      path = "/v1/securities/{isin}/daily-market-summaries",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SummaryPageResponse forSecurity(
      @PathVariable String isin,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) @Nullable LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) @Nullable LocalDate toDate,
      @RequestParam(required = false) @Nullable String pageToken) {
    String normalized = isin.strip().toUpperCase(Locale.ROOT);
    // No ISIN is blank, very long, or holds a control character, which PostgreSQL text cannot
    // always store
    if (normalized.isEmpty()
        || normalized.length() > SecurityController.MAX_ISIN_LENGTH
        || normalized.chars().anyMatch(Character::isISOControl)) {
      throw notFound();
    }
    try {
      return listSummaries
          .forSecurity(Isin.of(normalized), fromDate, toDate, pageToken)
          .map(SummaryPageResponse::of)
          .orElseThrow(DailyMarketSummaryController::notFound);
    } catch (InvalidQueryException e) {
      throw new ApiProblemException(ProblemType.INVALID_REQUEST, e.detail());
    }
  }

  /**
   * Returns one page of the trade date's summaries, by ISIN.
   *
   * @throws ApiProblemException {@code 400} if the exchange filter is blank or the token is not
   *     valid
   */
  @GetMapping(path = "/v1/daily-market-summaries", produces = MediaType.APPLICATION_JSON_VALUE)
  public SummaryPageResponse forTradeDate(
      @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate tradeDate,
      @RequestParam(required = false) @Nullable String exchangeName,
      @RequestParam(required = false) @Nullable String pageToken) {
    try {
      return SummaryPageResponse.of(listSummaries.forTradeDate(tradeDate, exchangeName, pageToken));
    } catch (InvalidQueryException e) {
      throw new ApiProblemException(ProblemType.INVALID_REQUEST, e.detail());
    }
  }

  private static ApiProblemException notFound() {
    return new ApiProblemException(ProblemType.NOT_FOUND, SecurityController.NOT_FOUND);
  }
}
