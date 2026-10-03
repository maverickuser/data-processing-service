package com.bondplatform.dataprocessing.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobRunTypesTest {

  @Test
  void outcomeNeedsTerminalStatusCountsAndNonNegativeErrors() {
    assertThat(new JobOutcome(JobStatus.COMPLETED_WITH_ERRORS, "{}", 3).errorCount()).isEqualTo(3);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JobOutcome(JobStatus.RETRY_PENDING, "{}", 0))
        .withMessageContaining("terminal");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JobOutcome(JobStatus.COMPLETED, " ", 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JobOutcome(JobStatus.FAILED, "{}", -1));
  }

  @Test
  @SuppressWarnings("NullAway") // The job is irrelevant to the check under test.
  void attemptNumbersStartAtOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ClaimedJob(null, UUID.randomUUID(), 0));
  }
}
