package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.domain.IsinPage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The body of {@code GET /v1/securities}, as the read OpenAPI defines it. */
record IsinPageResponse(List<String> items, @Nullable String nextToken) {

  static IsinPageResponse of(IsinPage page) {
    return new IsinPageResponse(page.items(), page.nextToken());
  }
}
