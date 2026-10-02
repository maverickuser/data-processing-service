package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobIdTest {

  private static final String TEXT = "0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10";

  @Test
  void parsesAndPrintsUuidText() {
    JobId jobId = JobId.parse(TEXT);

    assertThat(jobId.value()).isEqualTo(UUID.fromString(TEXT));
    assertThat(jobId).hasToString(TEXT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"job-123", "", "1-1-1-1-1", "0b6f0a526b1e4d0c9f432f3a5d1c7e10"})
  void rejectsTextThatIsNotCanonicalUuidText(String text) {
    assertThatIllegalArgumentException().isThrownBy(() -> JobId.parse(text));
  }

  @Test
  void acceptsUppercaseHexDigits() {
    assertThat(JobId.parse(TEXT.toUpperCase(Locale.ROOT))).isEqualTo(JobId.parse(TEXT));
  }

  @Test
  void rejectsMissingValue() {
    assertThatNullPointerException().isThrownBy(() -> new JobId(nullValue()));
  }

  @SuppressWarnings("NullAway")
  private static UUID nullValue() {
    return null;
  }
}
