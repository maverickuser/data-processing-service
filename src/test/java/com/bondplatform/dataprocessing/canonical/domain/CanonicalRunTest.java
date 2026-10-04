package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalRunTest {

  @Test
  void objectKeyNamesJobAndAttempt() {
    assertThat(GoldenBhavcopy.RUN.objectKey())
        .isEqualTo("canonical/0190f3a0-0000-7000-8000-000000000001/attempt-2/canonical.jsonl");
  }

  @Test
  void attemptNumbersStartAtOne() {
    assertThatThrownBy(
            () ->
                new CanonicalRun(
                    new JobId(UUID.randomUUID()), 0, LocalDate.EPOCH, "b", "k", "s", "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
