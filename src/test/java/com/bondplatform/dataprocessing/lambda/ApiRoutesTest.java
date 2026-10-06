package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.admission.adapter.web.EventIngestionController;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.admission.domain.SubmissionValidator;
import com.bondplatform.dataprocessing.review.adapter.web.DailyMarketSummaryController;
import com.bondplatform.dataprocessing.review.adapter.web.JobStatusController;
import com.bondplatform.dataprocessing.review.adapter.web.SecurityController;
import com.bondplatform.dataprocessing.review.application.GetJobStatus;
import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.review.application.ListDailyMarketSummaries;
import com.bondplatform.dataprocessing.review.application.ListJobErrors;
import com.bondplatform.dataprocessing.shared.adapter.web.PublicApiProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The read API function and the submission function each serve only their own routes (LLD section
 * 23.1): the public, unauthenticated read function has no route that writes.
 */
class ApiRoutesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(SubmissionValidator.class, () -> mock(SubmissionValidator.class))
          .withBean(AdmitSubmission.class, () -> mock(AdmitSubmission.class))
          .withBean(
              PublicApiProperties.class,
              () -> new PublicApiProperties(URI.create("https://processing.example")))
          .withBean(GetSecurity.class, () -> mock(GetSecurity.class))
          .withBean(GetJobStatus.class, () -> mock(GetJobStatus.class))
          .withBean(ListJobErrors.class, () -> mock(ListJobErrors.class))
          .withBean(ListDailyMarketSummaries.class, () -> mock(ListDailyMarketSummaries.class))
          .withUserConfiguration(
              EventIngestionController.class,
              SecurityController.class,
              DailyMarketSummaryController.class,
              JobStatusController.class);

  @Test
  void readFunctionServesOnlyTheReadRoutes() {
    runner
        .withPropertyValues("data-processing.api.submission-routes=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(EventIngestionController.class);
              assertThat(context)
                  .hasSingleBean(SecurityController.class)
                  .hasSingleBean(DailyMarketSummaryController.class)
                  .hasSingleBean(JobStatusController.class);
            });
  }

  @Test
  void submissionFunctionServesOnlyTheSubmissionRoute() {
    runner
        .withPropertyValues("data-processing.api.read-routes=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EventIngestionController.class);
              assertThat(context)
                  .doesNotHaveBean(SecurityController.class)
                  .doesNotHaveBean(DailyMarketSummaryController.class)
                  .doesNotHaveBean(JobStatusController.class);
            });
  }

  @Test
  void localRunsServeEveryRoute() {
    runner.run(
        context ->
            assertThat(context)
                .hasSingleBean(EventIngestionController.class)
                .hasSingleBean(SecurityController.class)
                .hasSingleBean(DailyMarketSummaryController.class)
                .hasSingleBean(JobStatusController.class));
  }
}
