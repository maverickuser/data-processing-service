package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  @CsvSource({
    "400, INVALID_REQUEST",
    "404, NOT_FOUND",
    "405, METHOD_NOT_ALLOWED",
    "406, NOT_ACCEPTABLE",
    "413, REQUEST_TOO_LARGE",
    "415, UNSUPPORTED_MEDIA_TYPE",
    "503, SERVICE_UNAVAILABLE",
    "500, INTERNAL_ERROR"
  })
  void frameworkStatusKeepsItsOwnType(int status, ProblemType expected) {
    assertThat(ProblemType.forFrameworkStatus(status)).isEqualTo(expected);
    assertThat(expected.status()).isEqualTo(status);
  }

  @ParameterizedTest
  @CsvSource({"409, INVALID_REQUEST", "422, INVALID_REQUEST", "429, INVALID_REQUEST"})
  void clientStatusWithoutFrameworkTypeIsInvalidRequest(int status, ProblemType expected) {
    assertThat(ProblemType.forFrameworkStatus(status)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"501, INTERNAL_ERROR", "502, INTERNAL_ERROR", "302, INTERNAL_ERROR"})
  void anyOtherStatusIsInternalError(int status, ProblemType expected) {
    assertThat(ProblemType.forFrameworkStatus(status)).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(ProblemType.class)
  void everyTypeHasAnErrorStatus(ProblemType type) {
    assertThat(type.status()).isBetween(400, 599);
  }
}
