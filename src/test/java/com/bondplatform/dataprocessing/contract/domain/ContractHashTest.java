package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Test case U-CON-04. */
class ContractHashTest {

  @Test
  void identicalContentHasTheSameHash() {
    assertThat(hash("id: a\nversion: v1\n")).isEqualTo(hash("id: a\nversion: v1\n"));
  }

  @Test
  void anyChangeInContentChangesTheHash() {
    assertThat(hash("id: a\nversion: v1\n")).isNotEqualTo(hash("id: a\nversion: v1 \n"));
  }

  @Test
  void lineEndingsArePartOfTheContent() {
    assertThat(hash("id: a\nversion: v1\n")).isNotEqualTo(hash("id: a\r\nversion: v1\r\n"));
  }

  @Test
  void hashIsLabelledSha256Hex() {
    assertThat(hash(""))
        .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  private static String hash(String text) {
    return ContractHash.of(text.getBytes(StandardCharsets.UTF_8));
  }
}
