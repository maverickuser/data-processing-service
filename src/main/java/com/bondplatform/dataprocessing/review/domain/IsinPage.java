package com.bondplatform.dataprocessing.review.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of known ISINs (LLD section 20.4).
 *
 * @param items the ISINs in ascending order, at most {@value #SIZE}
 * @param nextToken the token for the next page, or {@code null} on the last page
 */
public record IsinPage(List<String> items, @Nullable String nextToken) {

  /** The most ISINs on a page. */
  public static final int SIZE = 50;

  /**
   * Copies the items.
   *
   * @throws IllegalArgumentException if there are more than {@value #SIZE}
   */
  public IsinPage {
    items = List.copyOf(items);
    if (items.size() > SIZE) {
      throw new IllegalArgumentException(items.size() + " ISINs do not fit on one page");
    }
  }
}
