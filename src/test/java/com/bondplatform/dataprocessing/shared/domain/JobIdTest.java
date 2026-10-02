package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobIdTest {

  private static final String TEXT = "0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10";

  @Test
  void parsesAndPrintsUuidText() {
    JobId jobId = JobId.parse(TEXT);

    assertThat(jobId.value()).isEqualTo(UUID.fromString(TEXT));
    assertThat(jobId).hasToString(TEXT);
  }

  @Test
  void rejectsNonUuidText() {
    assertThatIllegalArgumentException().isThrownBy(() -> JobId.parse("job-123"));
  }
}
