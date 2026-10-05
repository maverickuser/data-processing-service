package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.domain.ErrorPage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The body of {@code GET /v1/processing-jobs/{jobId}/errors}, as the read OpenAPI defines it. */
record ErrorPageResponse(List<JobStatusResponse.ErrorResponse> items, @Nullable String nextToken) {

  static ErrorPageResponse of(ErrorPage page) {
    return new ErrorPageResponse(
        page.items().stream().map(JobStatusResponse.ErrorResponse::of).toList(), page.nextToken());
  }
}
