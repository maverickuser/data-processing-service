package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves a job's status, counts and error preview (LLD sections 16 and 20.3). */
@RestController
public class JobStatusController {

  static final String NOT_FOUND = "No processing job has this ID.";

  private final GetJobStatus getJobStatus;

  /** Creates the controller. */
  public JobStatusController(GetJobStatus getJobStatus) {
    this.getJobStatus = getJobStatus;
  }

  /**
   * Returns the job's status. An ID that is not a canonical UUID cannot name a job, so it is not
   * found rather than invalid.
   *
   * @throws ApiProblemException {@code 404} if no job has the ID
   */
  @GetMapping(path = "/v1/processing-jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public JobStatusResponse get(@PathVariable String jobId) {
    JobId id;
    try {
      id = JobId.parse(jobId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    return getJobStatus
        .find(id)
        .map(JobStatusResponse::of)
        .orElseThrow(JobStatusController::notFound);
  }

  private static ApiProblemException notFound() {
    return new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND);
  }
}
