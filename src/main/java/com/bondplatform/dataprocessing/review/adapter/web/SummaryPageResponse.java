package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.domain.DailyMarketSummaryView;
import com.bondplatform.dataprocessing.review.domain.SummaryPage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The body of both daily-market-summary endpoints, as the read OpenAPI defines it. */
record SummaryPageResponse(List<DailyMarketSummaryView> items, @Nullable String nextToken) {

  static SummaryPageResponse of(SummaryPage page) {
    return new SummaryPageResponse(page.items(), page.nextToken());
  }
}
