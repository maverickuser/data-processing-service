package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.application.InvalidQueryException;
import com.bondplatform.dataprocessing.review.application.ListJobErrors;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves a job's status, counts and error preview, and its full error list (LLD 16, 20.3). */
@RestController
@ConditionalOnBooleanProperty(name = "data-processing.api.read-routes", matchIfMissing = true)
public class JobStatusController {

  static final String NOT_FOUND = "No processing job has this ID.";

  private final GetJobStatus getJobStatus;
  private final ListJobErrors listJobErrors;

  /** Creates the controller. */
  public JobStatusController(GetJobStatus getJobStatus, ListJobErrors listJobErrors) {
    this.getJobStatus = getJobStatus;
    this.listJobErrors = listJobErrors;
  }

  /**
   * Returns the job's status. An ID that is not a canonical UUID cannot name a job, so it is not
   * found rather than invalid.
   *
   * @throws ApiProblemException {@code 404} if no job has the ID
   */
  @GetMapping(path = "/v1/processing-jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public JobStatusResponse get(@PathVariable String jobId) {
    return getJobStatus
        .find(id(jobId))
        .map(JobStatusResponse::of)
        .orElseThrow(JobStatusController::notFound);
  }

  /**
   * Returns one page of the job's errors.
   *
   * @throws ApiProblemException {@code 404} if no job has the ID, {@code 400} if the filter is
   *     blank or the page token was not issued for this job and filter
   */
  @GetMapping(
      path = "/v1/processing-jobs/{jobId}/errors",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ErrorPageResponse errors(
      @PathVariable String jobId,
      @RequestParam(required = false) @Nullable String isin,
      @RequestParam(required = false) @Nullable String pageToken) {
    try {
      return listJobErrors
          .list(id(jobId), isin, pageToken)
          .map(ErrorPageResponse::of)
          .orElseThrow(JobStatusController::notFound);
    } catch (InvalidQueryException e) {
      throw new ApiProblemException(ProblemType.INVALID_REQUEST, e.detail());
    }
  }

  /** An ID that is not a canonical UUID cannot name a job, so it is not found, not invalid. */
  private static JobId id(String jobId) {
    try {
      return JobId.parse(jobId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
  }

  private static ApiProblemException notFound() {
    return new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND);
  }
}
