package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.bondplatform.dataprocessing.contract.domain.SourceContract.Comparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContractModelTest {

  @Test
  void contractNameJoinsIdAndVersion() {
    ContractId id = new ContractId("nsdl-security-json", "v1");

    assertThat(id.name()).isEqualTo("nsdl-security-json-v1");
    assertThat(id).hasToString("nsdl-security-json-v1");
  }

  @Test
  void contractIdRejectsBlankParts() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ContractId(" ", "v1"));
    assertThatIllegalArgumentException().isThrownBy(() -> new ContractId("nsdl", ""));
  }

  @Test
  void datasetUrnAcceptsBondPlatformDatasets() {
    DatasetUrn urn = new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades");

    assertThat(urn).hasToString("urn:bond-platform:dataset:bse-debt-trades");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "bse-debt-trades", "urn:bond-platform:dataset:", "urn:other:dataset:x"})
  void datasetUrnRejectsAnythingElse(String value) {
    assertThatIllegalArgumentException().isThrownBy(() -> new DatasetUrn(value));
  }

  @ParameterizedTest
  @EnumSource(FieldType.class)
  void fieldTypeRoundTripsThroughItsContractName(FieldType type) {
    assertThat(FieldType.fromContractName(type.contractName())).isEqualTo(type);
  }

  @Test
  void fieldTypeNamesAreLowercaseAndExact() {
    assertThat(FieldType.DECIMAL.contractName()).isEqualTo("decimal");
    assertThatIllegalArgumentException().isThrownBy(() -> FieldType.fromContractName("Decimal"));
    assertThatIllegalArgumentException().isThrownBy(() -> FieldType.fromContractName("string"));
  }

  @Test
  void comparisonIsNamedAsInContracts() {
    assertThat(Comparison.fromContractName("greaterThanOrEqual"))
        .isEqualTo(Comparison.GREATER_THAN_OR_EQUAL);
    assertThat(Comparison.fromContractName("lessThanOrEqual"))
        .isEqualTo(Comparison.LESS_THAN_OR_EQUAL);
    assertThatIllegalArgumentException().isThrownBy(() -> Comparison.fromContractName("equals"));
  }
}
