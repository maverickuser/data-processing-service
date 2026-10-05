package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.publication.domain.JsonFileCounts;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetails;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a completely evaluated JSON request: the security's changed fields, its new collection
 * entries, and the job's outcome commit together, or nothing does (LLD sections 13.5 to 13.7).
 *
 * <p>In one transaction it creates the security if it has none, locks its row, stores only the
 * fields whose value differs from the stored one, appends the entries it does not already have, and
 * finishes the job. A request with identical data changes no row and no timestamp (I-JSON-02). A
 * security created here requests no details: the request being published is those details.
 */
public class PublishSecurityDetails {

  private final SecurityRepository securities;
  private final SecurityCollectionRepository collections;
  private final JobCompletion completion;
  private final Clock clock;

  /** Creates the use case. */
  public PublishSecurityDetails(
      SecurityRepository securities,
      SecurityCollectionRepository collections,
      JobCompletion completion,
      Clock clock) {
    this.securities = securities;
    this.collections = collections;
    this.completion = completion;
    this.clock = clock;
  }

  /**
   * Stores the request's changes and finishes the job.
   *
   * @param job the attempt being finished
   * @param details the request's values after both stages
   * @param files how the request's files and fields fared in stage 1
   * @param status the request's status; {@code FAILED} publishes nothing
   * @param errorCount the errors the attempt recorded
   * @return the outcome the job was finished with
   * @throws IllegalStateException if the job is not running this attempt; nothing is stored
   */
  @Transactional
  public JobOutcome publish(
      ClaimedJob job,
      SecurityDetails details,
      JsonFileCounts files,
      JobStatus status,
      int errorCount) {
    int fieldsChanged = 0;
    int entriesAppended = 0;
    if (status != JobStatus.FAILED) {
      // PostgreSQL keeps microseconds, so a stored time reads back equal to this one.
      Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
      securities.insertMissing(List.of(details.isin()), now);
      SecurityScalars changes =
          details.scalars().changesFrom(securities.lockValues(details.isin()));
      securities.applyChanges(details.isin(), changes, now);
      fieldsChanged = changes.fields().size() + changes.cleared().size();
      entriesAppended = append(details.collections(), now);
    }
    JobOutcome outcome = files.outcome(status, fieldsChanged, entriesAppended, errorCount);
    completion.complete(job, outcome);
    return outcome;
  }

  private int append(SecurityCollections entries, Instant now) {
    return collections.appendCashFlows(entries.cashFlows(), now)
        + collections.appendListings(entries.listings(), now)
        + collections.appendRatings(entries.ratings(), now)
        + collections.appendCollateralAssets(entries.collateralAssets(), now);
  }
}
