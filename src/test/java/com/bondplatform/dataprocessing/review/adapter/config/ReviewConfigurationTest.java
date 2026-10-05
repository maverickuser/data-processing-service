package com.bondplatform.dataprocessing.review.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.review.application.JobReviewRepository;
import com.bondplatform.dataprocessing.review.application.SecurityReviewRepository;
import org.junit.jupiter.api.Test;

class ReviewConfigurationTest {

  @Test
  void jobStatusReadsTheRepository() {
    assertThat(new ReviewConfiguration().getJobStatus(mock(JobReviewRepository.class))).isNotNull();
  }

  @Test
  void errorListReadsTheRepository() {
    assertThat(new ReviewConfiguration().listJobErrors(mock(JobReviewRepository.class)))
        .isNotNull();
  }

  @Test
  void securityViewReadsTheRepository() {
    assertThat(new ReviewConfiguration().getSecurity(mock(SecurityReviewRepository.class)))
        .isNotNull();
  }
}
