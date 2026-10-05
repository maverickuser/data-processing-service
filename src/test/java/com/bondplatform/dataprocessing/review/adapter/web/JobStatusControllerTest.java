package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.JobStatusView;
import com.bondplatform.dataprocessing.review.domain.RawValuePreview;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobStatusControllerTest {

  private static final String JOB = "0190f3a0-0000-7000-8000-000000000001";

  private final GetJobStatus getJobStatus = mock(GetJobStatus.class);
  private final JobStatusController controller = new JobStatusController(getJobStatus);

  @Test
  void foundJobIsAnsweredWithItsStatusAndErrors() {
    JobErrorView error =
        new JobErrorView(
            "error-1",
            "INVALID_DECIMAL",
            "INE001A07AB1",
            "BSE_fgroup01012026.csv",
            3,
            "close_price",
            null,
            new RawValuePreview(new BigDecimal("1.50"), false),
            "Not a number.",
            null);
    JobStatusView view =
        new JobStatusView(
            JOB,
            "urn:bond-platform:dataset:bse-debt-trades",
            JobStatus.COMPLETED_WITH_ERRORS,
            "exchange/BSE/trade-date/2026-01-01",
            1,
            Instant.parse("2026-01-01T14:30:00Z"),
            Instant.parse("2026-01-01T14:30:05Z"),
            Instant.parse("2026-01-01T14:30:20Z"),
            Map.of("sourceRecords", 1L),
            2,
            List.of(error));
    when(getJobStatus.find(JobId.parse(JOB))).thenReturn(Optional.of(view));

    JobStatusResponse response = controller.get(JOB);

    assertThat(response.jobId()).isEqualTo(JOB);
    assertThat(response.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(response.counts()).isEqualTo(Map.of("sourceRecords", 1L));
    assertThat(response.errorCount()).isEqualTo(2);
    assertThat(response.hasMoreErrors()).isTrue();
    assertThat(response.errorsUrl()).isEqualTo("/v1/processing-jobs/" + JOB + "/errors");
    assertThat(response.errors())
        .containsExactly(
            new JobStatusResponse.ErrorResponse(
                "error-1",
                "INVALID_DECIMAL",
                "INE001A07AB1",
                "BSE_fgroup01012026.csv",
                3,
                "close_price",
                null,
                new BigDecimal("1.50"),
                false,
                "Not a number.",
                null));
  }

  @Test
  void unknownJobIsNotFound() {
    when(getJobStatus.find(JobId.parse(JOB))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.get(JOB))
        .isInstanceOfSatisfying(
            ApiProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND));
  }

  // No 400 is documented: an ID that is not a canonical UUID names no job
  @Test
  void idThatIsNotCanonicalUuidIsNotFound() {
    assertThatThrownBy(() -> controller.get("job-123"))
        .isInstanceOfSatisfying(
            ApiProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND));
    verifyNoInteractions(getJobStatus);
  }
}
