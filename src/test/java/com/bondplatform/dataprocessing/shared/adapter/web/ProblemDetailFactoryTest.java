package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailFactoryTest {

  private static final UUID CORRELATION_ID =
      UUID.fromString("3df630b2-95d3-4f8f-8288-f7b0a1081b61");

  private final ProblemDetailFactory factory = new ProblemDetailFactory(() -> CORRELATION_ID);

  @Test
  void buildsEveryPropertyTheApiContractRequires() {
    ProblemDetail problem = factory.create(ProblemType.INVALID_REQUEST, "Missing header.");

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getType())
        .isEqualTo(URI.create("urn:bond-platform:problem:invalid-request"));
    assertThat(problem.getTitle()).isEqualTo("Invalid Request");
    assertThat(problem.getDetail()).isEqualTo("Missing header.");
    assertThat(problem.getInstance()).isEqualTo(URI.create("urn:uuid:" + CORRELATION_ID));
    assertThat(problem.getProperties())
        .containsEntry("code", "INVALID_REQUEST")
        .containsEntry("correlationId", CORRELATION_ID.toString());
  }
}
