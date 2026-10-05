package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.review.domain.ErrorPage;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import com.bondplatform.dataprocessing.review.domain.RawValuePreview;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ListJobErrorsTest {

  private static final JobId JOB = JobId.parse("0190f3a0-0000-7000-8000-000000000001");

  private final JobReviewRepository jobs = mock(JobReviewRepository.class);
  private final ListJobErrors listJobErrors = new ListJobErrors(jobs);

  @Test
  void unknownJobIsEmpty() {
    when(jobs.find(JOB)).thenReturn(Optional.empty());

    assertThat(listJobErrors.list(JOB, null, null)).isEmpty();
  }

  @Test
  void unfinishedJobHasNoErrorsYet() {
    givenJob(JobStatus.PROCESSING);

    assertThat(listJobErrors.list(JOB, null, null)).contains(new ErrorPage(List.of(), null));
    verify(jobs, never()).errors(any(), any(), anyLong(), anyInt());
  }

  @Test
  void fullPageWithMoreGivesTokenForTheLastShown() {
    givenJob(JobStatus.COMPLETED_WITH_ERRORS);
    when(jobs.errors(JOB, null, -1, 51)).thenReturn(listed(1, 51));

    ErrorPage page = listJobErrors.list(JOB, null, null).orElseThrow();

    assertThat(page.items()).hasSize(50);
    assertThat(PageToken.decode(Objects.requireNonNull(page.nextToken()), scope(""))).contains(50L);
  }

  @Test
  void nextPageStartsAfterTheTokensPositionAndLastPageHasNoToken() {
    givenJob(JobStatus.COMPLETED_WITH_ERRORS);
    when(jobs.errors(JOB, null, 50, 51)).thenReturn(listed(51, 70));

    ErrorPage page =
        listJobErrors.list(JOB, null, new PageToken(scope(""), 50).encode()).orElseThrow();

    assertThat(page.items()).hasSize(20);
    assertThat(page.nextToken()).isNull();
  }

  // AN-6: a last page of exactly fifty has no next token
  @Test
  void exactlyFiftyLeftIsTheLastPage() {
    givenJob(JobStatus.COMPLETED_WITH_ERRORS);
    when(jobs.errors(JOB, null, -1, 51)).thenReturn(listed(1, 50));

    ErrorPage page = listJobErrors.list(JOB, null, null).orElseThrow();

    assertThat(page.items()).hasSize(50);
    assertThat(page.nextToken()).isNull();
  }

  // I-READ-02 at the use case: the filter is trimmed and uppercased, and binds the token
  @Test
  void isinFilterIsNormalizedAndBindsTheToken() {
    givenJob(JobStatus.COMPLETED_WITH_ERRORS);
    when(jobs.errors(JOB, "INE831R08076", -1, 51)).thenReturn(listed(1, 51));

    ErrorPage page = listJobErrors.list(JOB, "  ine831r08076 ", null).orElseThrow();

    String token = Objects.requireNonNull(page.nextToken());
    assertThat(PageToken.decode(token, scope("INE831R08076"))).contains(50L);
    assertThatThrownBy(() -> listJobErrors.list(JOB, null, token))
        .isInstanceOf(InvalidQueryException.class);
  }

  // U-REV-03
  @Test
  void invalidTokenOrBlankFilterIsRejectedBeforeReading() {
    assertThatThrownBy(() -> listJobErrors.list(JOB, null, "changed"))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The pageToken is not valid.");
    assertThatThrownBy(() -> listJobErrors.list(JOB, "  ", null))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The isin filter must not be blank.");
    assertThatThrownBy(() -> listJobErrors.list(JOB, "INE" + (char) 0, null))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("The isin filter must not hold control characters.");
    verifyNoInteractions(jobs);
  }

  private void givenJob(JobStatus status) {
    when(jobs.find(JOB))
        .thenReturn(
            Optional.of(
                new JobReviewRepository.StoredJob(
                    JOB, "urn:x", status, "s", 1, Instant.EPOCH, null, null, null)));
  }

  private static String scope(String isin) {
    return "job-errors\n" + JOB + "\n" + isin;
  }

  private static List<JobReviewRepository.ListedError> listed(long first, long last) {
    return LongStream.rangeClosed(first, last)
        .mapToObj(
            n ->
                new JobReviewRepository.ListedError(
                    n,
                    new JobErrorView(
                        "e" + n,
                        "NEGATIVE_VALUE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        RawValuePreview.none(),
                        "Negative.",
                        null)))
        .toList();
  }
}
