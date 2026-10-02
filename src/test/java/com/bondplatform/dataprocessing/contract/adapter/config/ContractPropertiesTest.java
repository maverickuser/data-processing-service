package com.bondplatform.dataprocessing.contract.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContractPropertiesTest {

  @Test
  void acceptsListOfNamedPairs() {
    ContractPair pair = new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1");

    assertThat(new ContractProperties(List.of(pair)).contracts()).containsExactly(pair);
  }

  @Test
  void missingOrEmptyListIsRejectedByNamingTheProperty() {
    assertThatIllegalStateException()
        .isThrownBy(() -> new ContractProperties(null))
        .withMessageContaining("data-processing.contracts");
    assertThatIllegalStateException().isThrownBy(() -> new ContractProperties(List.of()));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "../contracts/x-v1", "Name-V1", "a/b", "name.yaml", "-v1"})
  void contractNameMustBePlainLowercaseName(String name) {
    assertThatIllegalStateException()
        .isThrownBy(() -> new ContractPair(name, "nsdl-security-mapping-v1"))
        .withMessageContaining("data-processing.contracts[].source");
    assertThatIllegalStateException()
        .isThrownBy(() -> new ContractPair("nsdl-security-json-v1", name))
        .withMessageContaining("data-processing.contracts[].mapping");
  }
}
