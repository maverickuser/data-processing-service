package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProblemTypeTest {

  @Test
  void exposesStatusTitleCodeAndTypeUri() {
    ProblemType type = ProblemType.IDEMPOTENCY_CONFLICT;

    assertThat(type.status()).isEqualTo(409);
    assertThat(type.title()).isEqualTo("Idempotency Conflict");
    assertThat(type.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(type.typeUri())
        .isEqualTo(URI.create("urn:bond-platform:problem:idempotency-conflict"));
  }

  @ParameterizedTest
  @EnumSource(ProblemType.class)
  void everyTypeHasAnErrorStatus(ProblemType type) {
    assertThat(type.status()).isBetween(400, 599);
  }
}
