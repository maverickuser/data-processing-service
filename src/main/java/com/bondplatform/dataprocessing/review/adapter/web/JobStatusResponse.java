package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.JobStatusView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The body of {@code GET /v1/processing-jobs/{jobId}}, as the read OpenAPI file defines it. */
record JobStatusResponse(
    String jobId,
    String dataset,
    JobStatus status,
    String subject,
    int attemptCount,
    Instant submittedAt,
    @Nullable Instant startedAt,
    @Nullable Instant completedAt,
    @Nullable Map<String, @Nullable Long> counts,
    long errorCount,
    List<ErrorResponse> errors,
    boolean hasMoreErrors,
    String errorsUrl) {

  static JobStatusResponse of(JobStatusView view) {
    return new JobStatusResponse(
        view.jobId(),
        view.dataset(),
        view.status(),
        view.subject(),
        view.attemptCount(),
        view.submittedAt(),
        view.startedAt(),
        view.completedAt(),
        view.counts(),
        view.errorCount(),
        view.errors().stream().map(ErrorResponse::of).toList(),
        view.hasMoreErrors(),
        view.errorsUrl());
  }

  /** One error; {@code rawValue} is left out when the error has no source value. */
  record ErrorResponse(
      String errorId,
      String code,
      @Nullable String isin,
      @Nullable String sourceFile,
      @Nullable Integer recordNumber,
      @Nullable String field,
      @Nullable String path,
      @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Object rawValue,
      boolean rawValueTruncated,
      String message,
      @Nullable String actionTaken) {

    static ErrorResponse of(JobErrorView error) {
      return new ErrorResponse(
          error.errorId(),
          error.code(),
          error.isin(),
          error.sourceFile(),
          error.recordNumber(),
          error.field(),
          error.path(),
          error.rawValue().value(),
          error.rawValue().truncated(),
          error.message(),
          error.actionTaken());
    }
  }
}
