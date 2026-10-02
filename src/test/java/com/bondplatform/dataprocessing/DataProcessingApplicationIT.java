package com.bondplatform.dataprocessing;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Verifies that the application context starts with the committed configuration. */
@SpringBootTest
class DataProcessingApplicationIT {

  @Autowired private ApplicationContext context;

  @Test
  void loadsBothDatasetContractsAtStartup() {
    assertThat(context.getBean(ContractRegistry.class).datasets())
        .extracting(DatasetUrn::value)
        .containsExactlyInAnyOrder(
            "urn:bond-platform:dataset:bse-debt-trades", "urn:bond-platform:dataset:nsdl-security");
  }

  @Test
  void startsApplicationContext() {
    assertThat(context.getBean(DataProcessingApplication.class)).isNotNull();
  }
}
