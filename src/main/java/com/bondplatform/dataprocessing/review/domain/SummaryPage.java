package com.bondplatform.dataprocessing.review.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of daily market summaries (LLD section 20.2).
 *
 * @param items the summaries, at most {@value #SIZE}
 * @param nextToken the token for the next page, or {@code null} on the last page
 */
public record SummaryPage(List<DailyMarketSummaryView> items, @Nullable String nextToken) {

  /** The most summaries on a page. */
  public static final int SIZE = 50;

  /**
   * Copies the items.
   *
   * @throws IllegalArgumentException if there are more than {@value #SIZE}
   */
  public SummaryPage {
    items = List.copyOf(items);
    if (items.size() > SIZE) {
      throw new IllegalArgumentException(items.size() + " summaries do not fit on one page");
    }
  }
}
