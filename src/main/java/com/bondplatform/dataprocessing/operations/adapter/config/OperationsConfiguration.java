package com.bondplatform.dataprocessing.operations.adapter.config;

import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.operations.application.RetentionStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the operational use cases. */
@Configuration(proxyBeanMethods = false)
public class OperationsConfiguration {

  /** Deletes data past its retention period. */
  @Bean
  public RetentionCleaner retentionCleaner(RetentionStore store, Clock clock) {
    return new RetentionCleaner(store, clock);
  }
}
