package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Reads stored daily market summaries for the read API, one keyset page at a time. */
public interface DailyMarketSummaryRepository {

  /** Returns whether a security has the ISIN. */
  boolean securityExists(Isin isin);

  /**
   * Returns up to {@code limit} of the ISIN's summaries within the inclusive dates, newest trade
   * date first then by exchange name, after {@code after} when given.
   */
  List<DailyMarketSummaryView> forSecurity(
      Isin isin,
      @Nullable LocalDate fromDate,
      @Nullable LocalDate toDate,
      @Nullable SecurityPosition after,
      int limit);

  /**
   * Returns up to {@code limit} summaries for the trade date, on the exchange when given, by ISIN
   * then exchange name, after {@code after} when given.
   */
  List<DailyMarketSummaryView> forTradeDate(
      LocalDate tradeDate,
      @Nullable String exchangeName,
      @Nullable TradeDatePosition after,
      int limit);

  /** Where a page of one security's summaries ended. */
  record SecurityPosition(LocalDate tradeDate, String exchangeName) {}

  /** Where a page of one trade date's summaries ended. */
  record TradeDatePosition(String isin, String exchangeName) {}
}
