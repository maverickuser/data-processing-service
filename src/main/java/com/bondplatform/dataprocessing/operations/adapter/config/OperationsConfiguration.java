package com.bondplatform.dataprocessing.operations.adapter.config;

import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.operations.application.RetentionStore;
import com.bondplatform.dataprocessing.operations.application.StuckJobFailer;
import com.bondplatform.dataprocessing.operations.application.StuckJobStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/** Creates the operational use cases. */
@Configuration(proxyBeanMethods = false)
public class OperationsConfiguration {

  /** Deletes data past its retention period. */
  @Bean
  public RetentionCleaner retentionCleaner(RetentionStore store, Clock clock) {
    return new RetentionCleaner(store, clock);
  }

  /** Fails jobs that stopped making progress, allowing each the retry policy's attempts. */
  @Bean
  public StuckJobFailer stuckJobFailer(
      StuckJobStore store,
      TransactionOperations transactions,
      RetryPolicy retryPolicy,
      Clock clock) {
    return new StuckJobFailer(store, transactions, retryPolicy.maxAttempts(), clock);
  }
}
