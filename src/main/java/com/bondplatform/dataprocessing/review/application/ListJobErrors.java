package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.ErrorPage;
import com.bondplatform.dataprocessing.review.domain.JobErrorView;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Answers {@code GET /v1/processing-jobs/{jobId}/errors} (LLD section 16): the final attempt's
 * errors, {@value ErrorPage#SIZE} per page in a stable order, the same errors as the status
 * preview. An unfinished job has no errors yet.
 */
public class ListJobErrors {

  /** Longer than any value the filter could match; longer input is refused unread. */
  private static final int MAX_FILTER_LENGTH = 64;

  private final JobReviewRepository jobs;

  /** Creates the use case. */
  public ListJobErrors(JobReviewRepository jobs) {
    this.jobs = jobs;
  }

  /**
   * Returns one page of the job's errors, or empty if no job has this ID.
   *
   * @param isin an optional filter, trimmed and uppercased before matching
   * @param pageToken the previous page's {@code nextToken}, or {@code null} for the first page
   * @throws InvalidQueryException if the filter is blank or holds a control character, or the token
   *     was not issued for this job and filter
   */
  public Optional<ErrorPage> list(JobId jobId, @Nullable String isin, @Nullable String pageToken) {
    String filter = normalized(isin);
    String scope = "job-errors\n" + jobId + "\n" + (filter == null ? "" : filter);
    long after =
        pageToken == null
            ? -1
            : PageToken.decode(pageToken, scope)
                .flatMap(ListJobErrors::sequence)
                .orElseThrow(() -> new InvalidQueryException("The pageToken is not valid."));
    return jobs.find(jobId)
        .map(
            job ->
                job.status().isTerminal()
                    ? page(jobId, filter, after, scope)
                    : new ErrorPage(List.of(), null));
  }

  private ErrorPage page(JobId jobId, @Nullable String filter, long after, String scope) {
    List<JobReviewRepository.ListedError> listed =
        jobs.errors(jobId, filter, after, ErrorPage.SIZE + 1);
    boolean more = listed.size() > ErrorPage.SIZE;
    List<JobReviewRepository.ListedError> shown = more ? listed.subList(0, ErrorPage.SIZE) : listed;
    String next =
        more
            ? new PageToken(scope, Long.toString(shown.getLast().sequenceNumber())).encode()
            : null;
    List<JobErrorView> items = shown.stream().map(JobReviewRepository.ListedError::error).toList();
    return new ErrorPage(items, next);
  }

  private static Optional<Long> sequence(String position) {
    try {
      return Optional.of(Long.parseLong(position));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static @Nullable String normalized(@Nullable String isin) {
    if (isin == null) {
      return null;
    }
    String trimmed = isin.strip();
    if (trimmed.isEmpty()) {
      throw new InvalidQueryException("The isin filter must not be blank.");
    }
    // PostgreSQL text cannot hold U+0000, and no ISIN holds a control character (AN-1)
    if (trimmed.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidQueryException("The isin filter must not hold control characters.");
    }
    if (trimmed.length() > MAX_FILTER_LENGTH) {
      throw new InvalidQueryException("The isin filter is too long.");
    }
    return trimmed.toUpperCase(Locale.ROOT);
  }
}
