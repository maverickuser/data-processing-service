package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.NoHandlerFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(new ProblemDetailFactory(UUID::randomUUID));

  @Test
  void unknownRouteIsNotFound() {
    ProblemDetail problem =
        handler.handleUnknownRoute(
            new NoHandlerFoundException("GET", "/v1/unknown", HttpHeaders.EMPTY));

    assertThat(problem.getStatus()).isEqualTo(404);
    assertThat(problem.getProperties()).containsEntry("code", "NOT_FOUND");
  }

  @Test
  void unexpectedFailureIsInternalErrorWithoutLeakingTheCause() {
    ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("password=secret"));

    assertThat(problem.getStatus()).isEqualTo(500);
    assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(problem.getDetail()).doesNotContain("secret");
  }
}
