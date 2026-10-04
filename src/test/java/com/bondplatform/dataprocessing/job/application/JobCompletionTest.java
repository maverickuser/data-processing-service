package com.bondplatform.dataprocessing.job.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JobCompletionTest {

  private static final Instant NOW = Instant.parse("2026-01-02T10:00:00Z");
  private static final JobId JOB =
      new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001"));
  private static final UUID RUN = UUID.fromString("0190f3a0-0000-7000-8000-0000000000aa");

  private final JobRunRepository runs = mock(JobRunRepository.class);
  private final JobCompletion completion =
      new JobCompletion(runs, Clock.fixed(NOW, ZoneOffset.UTC));
  private final ClaimedJob job = claimed();

  @Test
  void recordsTheCanonicalFileOnTheAttemptsRun() {
    completion.recordCanonicalFile(job, "canonical/key");

    verify(runs).recordCanonicalFile(RUN, "canonical/key");
  }

  @Test
  void completesTheAttemptsRunAndTheJob() {
    JobOutcome outcome = new JobOutcome(JobStatus.COMPLETED, "{}", 0);

    completion.complete(job, outcome);

    verify(runs).completeRun(JOB, RUN, 2, outcome, NOW);
  }

  private static ClaimedJob claimed() {
    StoredJob stored = Mockito.mock(StoredJob.class);
    Mockito.when(stored.id()).thenReturn(JOB);
    return new ClaimedJob(stored, RUN, 2);
  }
}
