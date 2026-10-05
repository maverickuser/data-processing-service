package com.bondplatform.dataprocessing.review.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a job's errors (LLD section 16).
 *
 * @param items the errors, at most {@value #SIZE}
 * @param nextToken the token for the next page, or {@code null} on the last page
 */
public record ErrorPage(List<JobErrorView> items, @Nullable String nextToken) {

  /** The most errors on a page. */
  public static final int SIZE = 50;

  /**
   * Copies the items.
   *
   * @throws IllegalArgumentException if there are more than {@value #SIZE}
   */
  public ErrorPage {
    items = List.copyOf(items);
    if (items.size() > SIZE) {
      throw new IllegalArgumentException(items.size() + " errors do not fit on one page");
    }
  }
}
