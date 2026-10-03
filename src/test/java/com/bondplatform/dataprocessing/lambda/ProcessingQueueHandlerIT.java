package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmissionReceipt;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * The worker's Lambda entry point, started as Lambda starts it and pointed at the test database
 * through system properties. No dataset handler exists yet, so these cases cover the messages that
 * need none: redeliveries, unknown jobs, and malformed bodies.
 */
class ProcessingQueueHandlerIT extends PostgresIntegrationTest {

  private static final List<String> DATABASE_PROPERTIES =
      List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password");

  private static @Nullable ProcessingQueueHandler handler;

  @Autowired private Environment environment;
  @Autowired private AdmitSubmission admitSubmission;

  @BeforeEach
  void startFunctionOnce() {
    if (handler == null) {
      for (String property : DATABASE_PROPERTIES) {
        System.setProperty(property, environment.getRequiredProperty(property));
      }
      handler = new ProcessingQueueHandler();
    }
  }

  @AfterAll
  static void forgetTheDatabase() {
    DATABASE_PROPERTIES.forEach(System::clearProperty);
    handler = null;
  }

  // U-JOB-02 through the entry point
  @Test
  void redeliveredMessageOfFinishedJobIsAcknowledgedAndStartsNoRun() {
    AdmissionReceipt receipt = admit();
    jdbc.sql("UPDATE data_processing.ingestion_requests SET status = 'COMPLETED'").update();

    SQSBatchResponse response = handle("{\"jobId\": \"" + receipt.jobId() + "\"}");

    assertThat(response.getBatchItemFailures()).isEmpty();
    assertThat(
            jdbc.sql("SELECT count(*) FROM data_processing.processing_runs")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void messageForUnknownJobIsAcknowledged() {
    assertThat(handle("{\"jobId\": \"" + UUID.randomUUID() + "\"}").getBatchItemFailures())
        .isEmpty();
  }

  @Test
  void malformedMessageIsAcknowledgedAndChangesNothing() {
    admit();

    assertThat(handle("{\"job\": 1}").getBatchItemFailures()).isEmpty();

    assertThat(
            jdbc.sql("SELECT status FROM data_processing.ingestion_requests")
                .query(String.class)
                .single())
        .isEqualTo("QUEUED");
  }

  private AdmissionReceipt admit() {
    Map<String, Object> event =
        SubmissionEvents.nsdl(
            "run_" + UUID.randomUUID().toString().substring(0, 8), "INE121A07QY9");
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event);
  }

  private static SQSBatchResponse handle(String body) {
    SQSMessage message = new SQSMessage();
    message.setMessageId("m1");
    message.setBody(body);
    SQSEvent event = new SQSEvent();
    event.setRecords(List.of(message));
    return Objects.requireNonNull(handler).handleRequest(event, new FixedLambdaContext());
  }
}
