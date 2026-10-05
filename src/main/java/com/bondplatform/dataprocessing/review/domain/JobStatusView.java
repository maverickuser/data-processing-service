package com.bondplatform.dataprocessing.review.domain;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A job as {@code GET /v1/processing-jobs/{jobId}} shows it (LLD section 20.3).
 *
 * @param jobId the job
 * @param dataset the dataset URN
 * @param status the job's status
 * @param subject the submission's subject
 * @param attemptCount the attempts started
 * @param submittedAt when the job was accepted
 * @param startedAt when its first attempt started, if it has
 * @param completedAt when it finished, if it has
 * @param counts the dataset's counts in stored order, {@code null} until the job finishes; a count
 *     that could not be determined is {@code null}
 * @param errorCount the errors of the final attempt; warnings are not errors
 * @param errors the first errors, at most {@value #PREVIEW_SIZE}
 */
public record JobStatusView(
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
    List<JobErrorView> errors) {

  /** The most errors shown with the status. */
  public static final int PREVIEW_SIZE = 5;

  /**
   * Copies the counts and errors.
   *
   * @throws IllegalArgumentException if there are more errors than the preview shows, or more than
   *     the error count
   */
  public JobStatusView {
    if (counts != null) {
      counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
    errors = List.copyOf(errors);
    if (errors.size() > PREVIEW_SIZE || errors.size() > errorCount) {
      throw new IllegalArgumentException(
          errors.size() + " errors cannot preview an error count of " + errorCount);
    }
  }

  /** Returns whether the job has errors beyond the preview. */
  public boolean hasMoreErrors() {
    return errorCount > errors.size();
  }

  /** Returns where the job's full error list is, as a path. */
  public String errorsUrl() {
    return "/v1/processing-jobs/" + jobId + "/errors";
  }
}
