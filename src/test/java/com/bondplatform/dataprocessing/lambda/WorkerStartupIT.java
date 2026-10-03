package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The worker function starts as Lambda starts it, without a web server and before any database is
 * reachable, and reports a message it cannot run as failed. Needs no Docker.
 */
class WorkerStartupIT {

  /** Nothing listens here, and the attempt to connect gives up quickly. */
  private static final Map<String, String> UNREACHABLE_DATABASE =
      Map.of(
          "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unreachable",
          "spring.datasource.hikari.connection-timeout", "250");

  private static ProcessingQueueHandler handler;

  @BeforeAll
  static void startFunction() {
    UNREACHABLE_DATABASE.forEach(System::setProperty);
    handler = new ProcessingQueueHandler();
  }

  @AfterAll
  static void forgetTheDatabaseSettings() {
    UNREACHABLE_DATABASE.keySet().forEach(System::clearProperty);
  }

  @Test
  void jobThatCannotBeRunWhileTheDatabaseIsUnreachableIsDeliveredAgain() {
    SQSMessage message = new SQSMessage();
    message.setMessageId("m1");
    message.setBody("{\"jobId\": \"" + UUID.randomUUID() + "\"}");
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(message));

    SQSBatchResponse response = handler.handleRequest(event, new FixedLambdaContext());

    assertThat(response.getBatchItemFailures())
        .singleElement()
        .satisfies(failure -> assertThat(failure.getItemIdentifier()).isEqualTo("m1"));
  }
}
