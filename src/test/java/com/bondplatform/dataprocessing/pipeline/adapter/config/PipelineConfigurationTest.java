package com.bondplatform.dataprocessing.pipeline.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.publication.application.PublishDailyMarketSummaries;
import com.bondplatform.dataprocessing.source.application.LoadManifest;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class PipelineConfigurationTest {

  @Test
  void bhavcopyHandlerProcessesTheBseDataset() {
    assertThat(
            new PipelineConfiguration()
                .bhavcopyHandler(
                    new ContractRegistry(List.of(BhavcopyContract.PINNED)),
                    RuleRegistry.standard(),
                    mock(LoadManifest.class),
                    mock(SourceObjectReader.class),
                    mock(CanonicalFileStore.class),
                    mock(RejectedRecordStore.class),
                    mock(JobCompletion.class),
                    mock(PublishDailyMarketSummaries.class),
                    TransactionOperations.withoutTransaction())
                .dataset())
        .isEqualTo(PipelineConfiguration.BSE_DEBT_TRADES);
  }
}
