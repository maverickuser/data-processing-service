package com.bondplatform.dataprocessing.review.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.review.application.JobReviewRepository;
import org.junit.jupiter.api.Test;

class ReviewConfigurationTest {

  @Test
  void jobStatusReadsTheRepository() {
    assertThat(new ReviewConfiguration().getJobStatus(mock(JobReviewRepository.class))).isNotNull();
  }
}
