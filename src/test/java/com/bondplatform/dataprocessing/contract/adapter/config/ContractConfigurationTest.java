package com.bondplatform.dataprocessing.contract.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractConfigurationTest {

  @Test
  void buildsTheRegistryFromTheConfiguredPairs() {
    ContractConfiguration configuration = new ContractConfiguration();
    ContractProperties properties =
        new ContractProperties(
            List.of(new ContractPair("nsdl-security-json-v4", "nsdl-security-mapping-v4")));

    ContractRegistry registry =
        configuration.contractRegistry(properties, configuration.ruleRegistry());

    assertThat(registry.datasets()).hasSize(1);
  }
}
