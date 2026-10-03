package com.bondplatform.dataprocessing.job.adapter.config;

import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the worker's retry policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class JobConfiguration {

  /** Returns the retry policy. */
  @Bean
  public RetryPolicy retryPolicy(WorkerProperties worker) {
    return new RetryPolicy(worker.retryDelays());
  }
}
