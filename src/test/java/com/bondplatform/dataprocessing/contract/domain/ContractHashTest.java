package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Test case U-CON-04. */
class ContractHashTest {

  @Test
  void identicalContentHasTheSameHash() {
    assertThat(ContractHash.of("id: a\nversion: v1\n"))
        .isEqualTo(ContractHash.of("id: a\nversion: v1\n"));
  }

  @Test
  void anyChangeInContentChangesTheHash() {
    assertThat(ContractHash.of("id: a\nversion: v1\n"))
        .isNotEqualTo(ContractHash.of("id: a\nversion: v1 \n"));
  }

  @Test
  void hashIsLabelledSha256Hex() {
    assertThat(ContractHash.of(""))
        .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
