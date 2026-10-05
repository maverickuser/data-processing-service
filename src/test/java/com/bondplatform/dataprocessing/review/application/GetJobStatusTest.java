package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.JobStatusView;
import com.bondplatform.dataprocessing.review.domain.RawValuePreview;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class GetJobStatusTest {

  private static final JobId JOB = JobId.parse("0190f3a0-0000-7000-8000-000000000001");
  private static final String BSE = "urn:bond-platform:dataset:bse-debt-trades";
  private static final Instant SUBMITTED = Instant.parse("2026-01-01T14:30:00Z");
  private static final Instant STARTED = Instant.parse("2026-01-01T14:30:05Z");
  private static final Instant COMPLETED = Instant.parse("2026-01-01T14:30:20Z");
  private static final Map<String, @Nullable Long> COUNTS =
      Map.of("sourceRecords", 2L, "acceptedRows", 1L, "invalidRows", 1L, "supersededRows", 0L);

  private final JobReviewRepository jobs = mock(JobReviewRepository.class);
  private final GetJobStatus getJobStatus = new GetJobStatus(jobs);

  @Test
  void unknownJobIsNotFound() {
    when(jobs.find(JOB)).thenReturn(Optional.empty());

    assertThat(getJobStatus.find(JOB)).isEmpty();
  }

  // LLD 20.3: an unfinished job shows no counts, completion time or errors
  @Test
  void unfinishedJobShowsNoCountsCompletionOrErrors() {
    for (JobStatus status :
        List.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_PENDING)) {
      when(jobs.find(JOB)).thenReturn(Optional.of(stored(status, COMPLETED, COUNTS)));

      JobStatusView view = getJobStatus.find(JOB).orElseThrow();

      assertThat(view.status()).isEqualTo(status);
      assertThat(view.counts()).isNull();
      assertThat(view.completedAt()).isNull();
      assertThat(view.errorCount()).isZero();
      assertThat(view.errors()).isEmpty();
    }
    verify(jobs, never()).errorCount(JOB);
  }

  // U-REV-01
  @Test
  void finishedJobPreviewsTheFirstFiveOfItsErrors() {
    when(jobs.find(JOB))
        .thenReturn(Optional.of(stored(JobStatus.COMPLETED_WITH_ERRORS, COMPLETED, COUNTS)));
    when(jobs.errorCount(JOB)).thenReturn(7L);
    when(jobs.firstErrors(JOB, 5)).thenReturn(errors(5));

    JobStatusView view = getJobStatus.find(JOB).orElseThrow();

    assertThat(view.jobId()).isEqualTo(JOB.toString());
    assertThat(view.dataset()).isEqualTo(BSE);
    assertThat(view.subject()).isEqualTo("exchange/BSE/trade-date/2026-01-01");
    assertThat(view.attemptCount()).isEqualTo(2);
    assertThat(view.submittedAt()).isEqualTo(SUBMITTED);
    assertThat(view.startedAt()).isEqualTo(STARTED);
    assertThat(view.completedAt()).isEqualTo(COMPLETED);
    assertThat(view.counts()).isEqualTo(COUNTS);
    assertThat(view.errorCount()).isEqualTo(7);
    assertThat(view.errors()).hasSize(5);
    assertThat(view.hasMoreErrors()).isTrue();
  }

  @Test
  void finishedJobWithoutErrorsDoesNotReadThem() {
    when(jobs.find(JOB)).thenReturn(Optional.of(stored(JobStatus.COMPLETED, COMPLETED, COUNTS)));
    when(jobs.errorCount(JOB)).thenReturn(0L);

    JobStatusView view = getJobStatus.find(JOB).orElseThrow();

    assertThat(view.errors()).isEmpty();
    assertThat(view.hasMoreErrors()).isFalse();
    verify(jobs, never()).firstErrors(ArgumentMatchers.any(), ArgumentMatchers.anyInt());
  }

  // LLD 20.3: a job that failed before counting reports each count as null
  @Test
  void failedJobWithoutCountsShowsThemAsNull() {
    when(jobs.find(JOB)).thenReturn(Optional.of(stored(JobStatus.FAILED, COMPLETED, null)));
    when(jobs.errorCount(JOB)).thenReturn(1L);
    when(jobs.firstErrors(JOB, 5)).thenReturn(errors(1));

    JobStatusView view = getJobStatus.find(JOB).orElseThrow();

    assertThat(Objects.requireNonNull(view.counts()))
        .containsOnlyKeys("sourceRecords", "acceptedRows", "invalidRows", "supersededRows")
        .allSatisfy((name, value) -> assertThat(value).isNull());
    assertThat(view.errors()).hasSize(1);
  }

  private static JobReviewRepository.StoredJob stored(
      JobStatus status, Instant completedAt, @Nullable Map<String, @Nullable Long> counts) {
    return new JobReviewRepository.StoredJob(
        JOB,
        BSE,
        status,
        "exchange/BSE/trade-date/2026-01-01",
        2,
        SUBMITTED,
        STARTED,
        completedAt,
        counts);
  }

  private static List<JobErrorView> errors(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(
            n ->
                new JobErrorView(
                    "error-" + n,
                    "NEGATIVE_VALUE",
                    null,
                    "a.csv",
                    n + 1,
                    "turnover",
                    null,
                    RawValuePreview.none(),
                    "Negative.",
                    null))
        .toList();
  }
}
