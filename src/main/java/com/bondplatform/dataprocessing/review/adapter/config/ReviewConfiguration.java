package com.bondplatform.dataprocessing.review.adapter.config;

import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.application.JobReviewRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the read use cases. */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

  /** Answers job status requests. */
  @Bean
  public GetJobStatus getJobStatus(JobReviewRepository jobs) {
    return new GetJobStatus(jobs);
  }
}
