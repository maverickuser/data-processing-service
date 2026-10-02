package com.bondplatform.dataprocessing.contract.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Starts the contract wiring with different configurations: a broken or missing contract must stop
 * the application rather than leave it running without that dataset.
 */
class ContractStartupIT {

  private static final String PREFIX = "data-processing.contracts[0].";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ContractConfiguration.class);

  @Test
  void startsWithValidContractPair() {
    runner
        .withPropertyValues(
            PREFIX + "source=nsdl-security-json-v1", PREFIX + "mapping=nsdl-security-mapping-v1")
        .run(context -> assertThat(context.getBean(ContractRegistry.class).datasets()).hasSize(1));
  }

  @Test
  void failsWhenNoContractsAreConfigured() {
    runner.run(
        context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("data-processing.contracts must list at least one"));
  }

  @Test
  void failsWhenThePairHasNoMapping() {
    runner
        .withPropertyValues(PREFIX + "source=nsdl-security-json-v1")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("data-processing.contracts[].mapping"));
  }

  @Test
  void failsWhenContractFileDoesNotExist() {
    runner
        .withPropertyValues(PREFIX + "source=absent-v1", PREFIX + "mapping=absent-mapping-v1")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("/contracts/absent-v1.yaml does not exist"));
  }

  @Test
  void failsWhenThePairIsInconsistent() {
    runner
        .withPropertyValues(
            PREFIX + "source=bse-debt-bhavcopy-csv-v1", PREFIX + "mapping=nsdl-security-mapping-v1")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("bse-debt-bhavcopy-csv-v1 with nsdl-security-mapping-v1")
                    .hasMessageContaining("is paired with"));
  }
}
