package com.bondplatform.dataprocessing.review.adapter.config;

import com.bondplatform.dataprocessing.review.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.review.application.JobReviewRepository;
import com.bondplatform.dataprocessing.review.application.ListDailyMarketSummaries;
import com.bondplatform.dataprocessing.review.application.ListJobErrors;
import com.bondplatform.dataprocessing.review.application.SecurityReviewRepository;
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

  /** Answers job error-list requests. */
  @Bean
  public ListJobErrors listJobErrors(JobReviewRepository jobs) {
    return new ListJobErrors(jobs);
  }

  /** Answers security view requests. */
  @Bean
  public GetSecurity getSecurity(SecurityReviewRepository securities) {
    return new GetSecurity(securities);
  }

  /** Answers both daily-market-summary requests. */
  @Bean
  public ListDailyMarketSummaries listDailyMarketSummaries(DailyMarketSummaryRepository summaries) {
    return new ListDailyMarketSummaries(summaries);
  }
}
