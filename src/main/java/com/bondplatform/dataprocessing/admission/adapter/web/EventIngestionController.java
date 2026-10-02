package com.bondplatform.dataprocessing.admission.adapter.web;

import com.bondplatform.dataprocessing.admission.application.AdmissionReceipt;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.admission.application.IdempotencyConflictException;
import com.bondplatform.dataprocessing.admission.domain.Submission;
import com.bondplatform.dataprocessing.admission.domain.SubmissionError;
import com.bondplatform.dataprocessing.admission.domain.SubmissionResult;
import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemError;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.adapter.web.PublicApiProperties;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/event-ingestions}: accepts a manifest submission from the fetch service (LLD
 * section 2.1).
 *
 * <p>It answers {@code 202} only after the submission is stored durably, and with the same receipt
 * when the same submission is sent again. Who may call it is decided by API Gateway's IAM
 * authorization, not here.
 */
@RestController
public class EventIngestionController {

  /** The only request content type: a structured-mode CloudEvent. */
  static final String CLOUD_EVENT_JSON = "application/cloudevents+json";

  /** The largest request body accepted, in bytes. */
  static final int MAX_BODY_BYTES = 65_536;

  private static final String ACCEPTED = "ACCEPTED";
  private static final String NOT_UTF_8 = "Use application/cloudevents+json with UTF-8 encoding.";
  private static final String TOO_LARGE = "The request body exceeds 65536 bytes.";
  private static final String MALFORMED =
      "The body must be one UTF-8 JSON object with no repeated names.";
  private static final String INVALID = "The submission is not valid; see errors.";
  private static final String CONFLICT =
      "This Idempotency-Key or event identity was already accepted with different content."
          + " Do not retry unchanged.";

  private final SubmissionValidator validator;
  private final AdmitSubmission admitSubmission;
  private final PublicApiProperties api;

  /** Creates the controller. */
  public EventIngestionController(
      SubmissionValidator validator, AdmitSubmission admitSubmission, PublicApiProperties api) {
    this.validator = validator;
    this.admitSubmission = admitSubmission;
    this.api = api;
  }

  /**
   * Validates and durably accepts one submission.
   *
   * <p>The body is a required parameter on purpose: with an optional body the framework skips the
   * content-type check whenever it cannot tell that a body is present, as behind API Gateway.
   *
   * @throws ApiProblemException with {@code 415} for another character set, {@code 413} for an
   *     oversize body, {@code 400} for a malformed or invalid submission, and {@code 409} when the
   *     key or event identity was accepted with other content
   */
  @PostMapping(
      path = "/v1/event-ingestions",
      consumes = CLOUD_EVENT_JSON,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AdmissionReceiptResponse> submit(
      @RequestHeader(HttpHeaders.CONTENT_TYPE) MediaType contentType,
      @RequestHeader(name = SubmissionValidator.IDEMPOTENCY_KEY_HEADER, required = false)
          @Nullable String idempotencyKey,
      @RequestBody byte[] body) {
    Charset charset = contentType.getCharset();
    if (charset != null && !charset.equals(StandardCharsets.UTF_8)) {
      throw new ApiProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, NOT_UTF_8);
    }
    if (body.length > MAX_BODY_BYTES) {
      throw new ApiProblemException(ProblemType.REQUEST_TOO_LARGE, TOO_LARGE);
    }
    Map<String, Object> event =
        SubmissionBodyParser.parse(body)
            .orElseThrow(() -> new ApiProblemException(ProblemType.INVALID_REQUEST, MALFORMED));
    AdmissionReceipt receipt = admit(validated(event, idempotencyKey), event);
    URI statusUrl = api.urlOf("/v1/processing-jobs/" + receipt.jobId());
    return ResponseEntity.accepted()
        .location(statusUrl)
        .body(
            new AdmissionReceiptResponse(
                receipt.jobId().toString(),
                ACCEPTED,
                receipt.eventId(),
                receipt.runId(),
                receipt.createdAt(),
                statusUrl));
  }

  private Submission validated(Map<String, Object> event, @Nullable String idempotencyKey) {
    return switch (validator.validate(event, idempotencyKey)) {
      case SubmissionResult.Valid valid -> valid.submission();
      case SubmissionResult.Invalid invalid ->
          throw new ApiProblemException(
              ProblemType.INVALID_REQUEST, INVALID, problemErrors(invalid.errors()));
    };
  }

  private AdmissionReceipt admit(Submission submission, Map<String, Object> event) {
    try {
      return admitSubmission.admit(submission, event);
    } catch (IdempotencyConflictException e) {
      throw new ApiProblemException(ProblemType.IDEMPOTENCY_CONFLICT, CONFLICT);
    }
  }

  private static List<ProblemError> problemErrors(List<SubmissionError> errors) {
    return errors.stream()
        .map(
            error ->
                new ProblemError(
                    error.location().name().toLowerCase(Locale.ROOT),
                    error.pointer(),
                    error.header(),
                    error.code().name(),
                    error.message()))
        .toList();
  }
}
