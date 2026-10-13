package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;
import org.junit.jupiter.api.Test;

/** Test case U-REV-10. */
class GetSecurityMetricsTest {

  @Test
  void returnsTheCountsTheRepositoryReads() {
    SecurityReviewRepository securities = mock(SecurityReviewRepository.class);
    SecurityMetrics metrics = new SecurityMetrics(4, 1, 2, 3, 2, 3);
    when(securities.metrics()).thenReturn(metrics);

    assertThat(new GetSecurityMetrics(securities).current()).isEqualTo(metrics);
  }
}
