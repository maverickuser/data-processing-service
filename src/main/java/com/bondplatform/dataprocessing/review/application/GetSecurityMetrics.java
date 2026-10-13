package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;

/** Answers {@code GET /v1/metrics} (LLD section 20.5): security counts as they are now. */
public class GetSecurityMetrics {

  private final SecurityReviewRepository securities;

  /** Creates the use case. */
  public GetSecurityMetrics(SecurityReviewRepository securities) {
    this.securities = securities;
  }

  /** Returns the counts, read in one statement so they describe the same moment. */
  public SecurityMetrics current() {
    return securities.metrics();
  }
}
