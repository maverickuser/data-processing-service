package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.adapter.web.MetricsController.MetricsResponse;
import com.bondplatform.dataprocessing.review.application.GetSecurityMetrics;
import com.bondplatform.dataprocessing.review.domain.SecurityMetrics;
import org.junit.jupiter.api.Test;

/** Test case U-REV-10. */
class MetricsControllerTest {

  @Test
  void showsEachCountUnderItsAgreedName() {
    GetSecurityMetrics getSecurityMetrics = mock(GetSecurityMetrics.class);
    when(getSecurityMetrics.current()).thenReturn(new SecurityMetrics(6, 1, 2, 3, 4, 5));

    assertThat(new MetricsController(getSecurityMetrics).metrics())
        .isEqualTo(new MetricsResponse(6, 1, 2, 3, 4, 5));
  }
}
