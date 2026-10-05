package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.JobCounts;
import com.bondplatform.dataprocessing.review.domain.JobStatusView;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.List;
import java.util.Optional;

/**
 * Answers {@code GET /v1/processing-jobs/{jobId}} (LLD section 20.3).
 *
 * <p>While a job is not finished it shows no counts, no completion time and no errors; a finished
 * job shows its counts (each {@code null} if it failed before counting), its final attempt's error
 * count and the first {@value JobStatusView#PREVIEW_SIZE} errors, the same errors, in the same
 * order, as the full list.
 */
public class GetJobStatus {

  private final JobReviewRepository jobs;

  /** Creates the use case. */
  public GetJobStatus(JobReviewRepository jobs) {
    this.jobs = jobs;
  }

  /** Returns the job's status, or empty if no job has this ID. */
  public Optional<JobStatusView> find(JobId jobId) {
    return jobs.find(jobId).map(this::view);
  }

  private JobStatusView view(JobReviewRepository.StoredJob job) {
    boolean finished = job.status().isTerminal();
    long errorCount = finished ? jobs.errorCount(job.jobId()) : 0;
    return new JobStatusView(
        job.jobId().toString(),
        job.dataset(),
        job.status(),
        job.subject(),
        job.attemptCount(),
        job.submittedAt(),
        job.startedAt(),
        finished ? job.completedAt() : null,
        finished ? JobCounts.ofFinished(job.dataset(), job.counts()) : null,
        errorCount,
        errorCount == 0 ? List.of() : jobs.firstErrors(job.jobId(), JobStatusView.PREVIEW_SIZE));
  }
}
