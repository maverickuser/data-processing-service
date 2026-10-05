package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** U-REV-01. */
class JobStatusViewTest {

  private static final String JOB = "0190f3a0-0000-7000-8000-000000000001";

  @Test
  void moreErrorsThanThePreviewAreFlagged() {
    JobStatusView view = view(12, errors(5));

    assertThat(view.errors()).hasSize(5);
    assertThat(view.hasMoreErrors()).isTrue();
  }

  @Test
  void allErrorsShownAreNotMore() {
    assertThat(view(3, errors(3)).hasMoreErrors()).isFalse();
    assertThat(view(0, List.of()).hasMoreErrors()).isFalse();
  }

  @Test
  void errorCountKeptAfterCleanupOfTheListIsNotMore() {
    assertThat(view(4, List.of()).hasMoreErrors()).isFalse();
  }

  @Test
  void previewHoldsAtMostFiveErrors() {
    assertThatThrownBy(() -> view(6, errors(6))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void previewCannotExceedTheErrorCount() {
    assertThatThrownBy(() -> view(1, errors(2))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorsUrlIsTheJobsErrorList() {
    assertThat(view(0, List.of()).errorsUrl()).isEqualTo("/v1/processing-jobs/" + JOB + "/errors");
  }

  @Test
  void countsKeepTheirOrderAndCannotChange() {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("sourceRecords", 2L);
    counts.put("acceptedRows", 2L);

    JobStatusView view =
        new JobStatusView(
            JOB,
            "urn:x",
            JobStatus.COMPLETED,
            "s",
            1,
            Instant.EPOCH,
            null,
            null,
            counts,
            0,
            List.of());

    Map<String, Long> shown = Objects.requireNonNull(view.counts());
    assertThat(shown)
        .containsExactly(Map.entry("sourceRecords", 2L), Map.entry("acceptedRows", 2L));
    assertThatThrownBy(() -> shown.put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
  }

  private static JobStatusView view(long errorCount, List<JobErrorView> errors) {
    return new JobStatusView(
        JOB,
        "urn:bond-platform:dataset:bse-debt-trades",
        JobStatus.COMPLETED_WITH_ERRORS,
        "exchange/BSE/trade-date/2026-01-01",
        1,
        Instant.parse("2026-01-01T14:30:00Z"),
        null,
        null,
        null,
        errorCount,
        errors);
  }

  static List<JobErrorView> errors(int count) {
    return IntStream.rangeClosed(1, count)
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
                    RawValuePreview.of("string", "-1"),
                    "Negative.",
                    null))
        .toList();
  }
}
