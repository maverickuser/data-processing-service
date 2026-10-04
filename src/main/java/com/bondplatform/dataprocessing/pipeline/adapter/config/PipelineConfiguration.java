package com.bondplatform.dataprocessing.pipeline.adapter.config;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.pipeline.application.BhavcopyHandler;
import com.bondplatform.dataprocessing.publication.application.PublishDailyMarketSummaries;
import com.bondplatform.dataprocessing.source.application.LoadManifest;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/** Creates a handler for each dataset the worker processes. */
@Configuration(proxyBeanMethods = false)
public class PipelineConfiguration {

  /** The BSE debt bhavcopy dataset. */
  static final DatasetUrn BSE_DEBT_TRADES =
      new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");

  /** Processes BSE debt bhavcopy jobs with the deployed bhavcopy contracts. */
  @Bean
  public BhavcopyHandler bhavcopyHandler(
      ContractRegistry contracts,
      RuleRegistry rules,
      LoadManifest manifests,
      SourceObjectReader sources,
      CanonicalFileStore canonicalFiles,
      RejectedRecordStore rejectedRecords,
      JobCompletion completion,
      PublishDailyMarketSummaries publication,
      TransactionOperations transactions) {
    return new BhavcopyHandler(
        contracts.contractsFor(BSE_DEBT_TRADES),
        rules,
        manifests,
        sources,
        canonicalFiles,
        rejectedRecords,
        completion,
        publication,
        transactions);
  }
}
