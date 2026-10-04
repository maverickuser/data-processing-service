package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a completely evaluated CSV file: its summaries and the job's outcome commit together,
 * or nothing does (LLD sections 7 and 8.1).
 *
 * <p>In one transaction it creates an ISIN-only security for each ISIN that has none, upserts every
 * summary, and finishes the job. A failure at any step rolls back every summary and security of the
 * file.
 */
@Service
public class PublishDailyMarketSummaries {

  private final SecurityRepository securities;
  private final DailyMarketSummaryRepository summaries;
  private final JobCompletion completion;
  private final Clock clock;

  /** Creates the use case. */
  public PublishDailyMarketSummaries(
      SecurityRepository securities,
      DailyMarketSummaryRepository summaries,
      JobCompletion completion,
      Clock clock) {
    this.securities = securities;
    this.summaries = summaries;
    this.completion = completion;
    this.clock = clock;
  }

  /**
   * Stores the file's accepted summaries and finishes the job with {@code outcome}.
   *
   * @param job the attempt being finished
   * @param accepted the summaries of every accepted row, in file order
   * @param outcome the job's final status, counts, and error count
   * @return the ISINs whose securities this call created, for information only: their
   *     security-details events must be written inside this transaction, never by the caller after
   *     it commits (LLD section 14.3; added in plan PR 29)
   * @throws IllegalStateException if the job is not running this attempt; nothing is stored
   */
  @Transactional
  public Set<Isin> publish(ClaimedJob job, List<DailyMarketSummary> accepted, JobOutcome outcome) {
    Instant now = Instant.now(clock);
    Set<Isin> isins = new LinkedHashSet<>();
    accepted.forEach(summary -> isins.add(summary.isin()));
    Set<Isin> created = securities.insertMissing(isins, now);
    summaries.upsertAll(accepted, now);
    completion.complete(job, outcome);
    return created;
  }
}
