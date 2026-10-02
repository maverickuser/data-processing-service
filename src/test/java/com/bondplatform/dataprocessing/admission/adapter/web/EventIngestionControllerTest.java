package com.bondplatform.dataprocessing.admission.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmissionReceipt;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.admission.application.IdempotencyConflictException;
import com.bondplatform.dataprocessing.admission.domain.CanonicalJson;
import com.bondplatform.dataprocessing.admission.domain.Submission;
import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemError;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.adapter.web.PublicApiProperties;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class EventIngestionControllerTest {

  private static final MediaType CLOUD_EVENT =
      MediaType.parseMediaType(EventIngestionController.CLOUD_EVENT_JSON);
  private static final UUID JOB = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final Instant CREATED_AT = Instant.parse("2026-09-27T14:31:00Z");
  private static final String STATUS_URL =
      "https://processing.kagent.app/v1/processing-jobs/550e8400-e29b-41d4-a716-446655440000";

  private final AdmitSubmission admitSubmission = mock(AdmitSubmission.class);
  private final EventIngestionController controller =
      new EventIngestionController(
          new SubmissionValidator(
              Set.of(
                  new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades"),
                  new DatasetUrn("urn:bond-platform:dataset:nsdl-security"))),
          admitSubmission,
          new PublicApiProperties(URI.create("https://processing.kagent.app/")));

  @Test
  void acceptedSubmissionAnswersWithItsReceiptAndWhereTheJobIs() {
    accepts();

    ResponseEntity<AdmissionReceiptResponse> response =
        controller.submit(CLOUD_EVENT, "run_202", bodyOf(nsdl()));

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(STATUS_URL));
    assertThat(response.getBody())
        .isEqualTo(
            new AdmissionReceiptResponse(
                JOB.toString(),
                "ACCEPTED",
                "urn:bond-platform:submission:run_202",
                "run_202",
                CREATED_AT,
                URI.create(STATUS_URL)));
  }

  @Test
  void admitsTheTypedSubmissionTogetherWithTheWholeParsedEvent() {
    accepts();
    Map<String, Object> event = nsdl();
    event.put("traceparent", "00-abc-01");

    controller.submit(
        MediaType.parseMediaType("application/cloudevents+json; charset=UTF-8"),
        "run_202",
        bodyOf(event));

    ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> parsed = ArgumentCaptor.forClass(Map.class);
    verify(admitSubmission).admit(submission.capture(), parsed.capture());
    assertThat(submission.getValue().runId()).isEqualTo("run_202");
    assertThat(CanonicalJson.of(parsed.getValue())).isEqualTo(CanonicalJson.of(event));
  }

  @Test
  void characterSetOtherThanUtf8IsUnsupportedMediaType() {
    ApiProblemException problem =
        problemOf(
            MediaType.parseMediaType("application/cloudevents+json; charset=ISO-8859-1"),
            "run_202",
            bodyOf(nsdl()));

    assertThat(problem.type()).isEqualTo(ProblemType.UNSUPPORTED_MEDIA_TYPE);
    verifyNoInteractions(admitSubmission);
  }

  @Test
  void bodyLargerThanTheLimitIsTooLargeAndOneAtTheLimitIsRead() {
    assertThat(
            problemOf(CLOUD_EVENT, "run_202", new byte[EventIngestionController.MAX_BODY_BYTES + 1])
                .type())
        .isEqualTo(ProblemType.REQUEST_TOO_LARGE);
    assertThat(
            problemOf(CLOUD_EVENT, "run_202", new byte[EventIngestionController.MAX_BODY_BYTES])
                .type())
        .isEqualTo(ProblemType.INVALID_REQUEST);
    verifyNoInteractions(admitSubmission);
  }

  @Test
  void emptyOrMalformedBodyIsInvalidRequestWithoutEchoingIt() {
    assertThat(problemOf(CLOUD_EVENT, "run_202", new byte[0]).type())
        .isEqualTo(ProblemType.INVALID_REQUEST);
    ApiProblemException malformed =
        problemOf(CLOUD_EVENT, "run_202", "{\"secret\": ".getBytes(StandardCharsets.UTF_8));

    assertThat(malformed.type()).isEqualTo(ProblemType.INVALID_REQUEST);
    assertThat(malformed.detail()).doesNotContain("secret");
    assertThat(malformed.errors()).isEmpty();
    verifyNoInteractions(admitSubmission);
  }

  @Test
  void invalidSubmissionListsEveryErrorInTheDocumentedShape() {
    Map<String, Object> event = nsdl();
    event.remove("subject");

    ApiProblemException problem = problemOf(CLOUD_EVENT, null, bodyOf(event));

    assertThat(problem.type()).isEqualTo(ProblemType.INVALID_REQUEST);
    assertThat(problem.errors())
        .contains(
            new ProblemError("body", "/subject", null, "REQUIRED", "Is required."),
            new ProblemError("header", null, "Idempotency-Key", "REQUIRED", "Is required."));
    verifyNoInteractions(admitSubmission);
  }

  @Test
  void keyThatDiffersFromTheRunIsInvalidRequest() {
    ApiProblemException problem = problemOf(CLOUD_EVENT, "run_999", bodyOf(nsdl()));

    assertThat(problem.errors())
        .extracting(ProblemError::header, ProblemError::code)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("Idempotency-Key", "MISMATCH"));
  }

  @Test
  void conflictingReuseIsIdempotencyConflict() {
    when(admitSubmission.admit(any(), any()))
        .thenThrow(new IdempotencyConflictException("run_202"));

    ApiProblemException problem = problemOf(CLOUD_EVENT, "run_202", bodyOf(nsdl()));

    assertThat(problem.type()).isEqualTo(ProblemType.IDEMPOTENCY_CONFLICT);
    assertThat(problem.detail()).contains("Do not retry unchanged");
  }

  private void accepts() {
    when(admitSubmission.admit(any(), any()))
        .thenReturn(
            new AdmissionReceipt(
                new JobId(JOB), "urn:bond-platform:submission:run_202", "run_202", CREATED_AT));
  }

  private ApiProblemException problemOf(MediaType contentType, @Nullable String key, byte[] body) {
    return catchThrowableOfType(
        ApiProblemException.class, () -> controller.submit(contentType, key, body));
  }

  private static Map<String, Object> nsdl() {
    return SubmissionEvents.nsdl("run_202", "INE121A07QY9");
  }

  private static byte[] bodyOf(Map<String, Object> event) {
    return CanonicalJson.of(event).getBytes(StandardCharsets.UTF_8);
  }
}
