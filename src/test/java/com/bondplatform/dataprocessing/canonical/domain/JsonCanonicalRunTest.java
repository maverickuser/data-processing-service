package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import org.junit.jupiter.api.Test;

class JsonCanonicalRunTest {

  private static final JobId JOB = JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10");

  @Test
  void keyMatchesTheCsvLayout() {
    JsonCanonicalRun run = run(2);

    assertThat(run.objectKey())
        .isEqualTo("canonical/0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10/attempt-2/canonical.jsonl");
  }

  @Test
  void attemptsStartAtOne() {
    assertThatThrownBy(() -> run(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static JsonCanonicalRun run(int attempt) {
    return new JsonCanonicalRun(
        JOB, attempt, Isin.of("INE831R08076"), "nsdl-security-json-v1", "nsdl-security-mapping-v1");
  }
}
