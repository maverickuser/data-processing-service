package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads jobs and the errors of their final attempt (LLD section 16).
 *
 * <p>A job's errors are those of its final processing run, the one with the highest attempt number:
 * first the run's own failure, if it has one, as an error without a field (PR 29b review AC-3),
 * then the run's stored issues in sequence order. Earlier attempts' errors are kept but never shown
 * (PR 35d review AL-1).
 */
public interface JobReviewRepository {

  /** Returns the job, if one has this ID. */
  Optional<StoredJob> find(JobId jobId);

  /** Returns how many errors the job's final attempt has. */
  long errorCount(JobId jobId);

  /** Returns the first errors of the job's final attempt, at most {@code limit}. */
  List<JobErrorView> firstErrors(JobId jobId, int limit);

  /**
   * A job's stored status fields.
   *
   * @param counts the stored counts in stored order, {@code null} when none were stored
   */
  record StoredJob(
      JobId jobId,
      String dataset,
      JobStatus status,
      String subject,
      int attemptCount,
      Instant submittedAt,
      @Nullable Instant startedAt,
      @Nullable Instant completedAt,
      @Nullable Map<String, @Nullable Long> counts) {}
}
