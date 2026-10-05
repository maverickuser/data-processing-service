package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.operations.application.RetentionResult;
import org.junit.jupiter.api.Test;

class RetentionHandlerTest {

  @Test
  void runsTheCleanupAndReportsWhatItDeleted() {
    RetentionHandler handler = new RetentionHandler(() -> new RetentionResult(4, 2, 9));

    String result = handler.handleRequest(new ScheduledEvent(), new FixedLambdaContext());

    assertThat(result).isEqualTo("deleted validationIssues=4 rejectedRecords=2 outboxEvents=9");
  }

  @Test
  void failedCleanupFailsTheInvocation() {
    RetentionHandler handler =
        new RetentionHandler(
            () -> {
              throw new IllegalStateException("database unavailable");
            });

    assertThatThrownBy(() -> handler.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isInstanceOf(IllegalStateException.class);
  }
}
