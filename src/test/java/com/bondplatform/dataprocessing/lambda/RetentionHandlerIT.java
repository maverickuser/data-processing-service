package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.operations.application.RetentionCleaner;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/** I-OPS-05 for the retention function: its daily event runs the cleanup and logs the counts. */
@ExtendWith(OutputCaptureExtension.class)
class RetentionHandlerIT extends PostgresIntegrationTest {

  @Autowired private RetentionCleaner cleaner;

  @Test
  void scheduledEventRunsTheCleanupAndLogsItAsJson(CapturedOutput output) {
    String result =
        new RetentionHandler(cleaner::clean)
            .handleRequest(new ScheduledEvent(), new FixedLambdaContext());

    assertThat(result).contains("outboxEvents=0");
    JsonNode logged = JsonLines.log(output, "Retention cleanup done");
    assertThat(logged.path("level").asString()).isEqualTo("INFO");
    assertThat(logged.path("logger_name").asString()).endsWith("RetentionHandler");
  }
}
