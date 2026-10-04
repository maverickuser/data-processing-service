package com.bondplatform.dataprocessing.pipeline.application;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFile;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordBuffer;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.CsvCanonicalizer;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.job.application.DatasetHandler;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.mapping.domain.DailyMarketSummaryMapper;
import com.bondplatform.dataprocessing.publication.application.PublishDailyMarketSummaries;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import com.bondplatform.dataprocessing.source.application.LoadManifest;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceProblem;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import com.bondplatform.dataprocessing.source.domain.VerifiedSource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Processes a BSE debt bhavcopy job from its manifest to its published summaries (LLD sections 3
 * and 8.1).
 *
 * <p>An attempt loads the manifest, reads the one listed CSV, and runs stage 1 over the whole file.
 * While rows are handed over it writes each to the canonical file, stores quarantined rows in
 * bounded batches, each in its own transaction, and maps accepted rows to summaries. Only then is
 * the canonical file stored and its key recorded on the run. Last, the summaries and the job's
 * outcome are published in one transaction. A file rejected as a whole fails the job without a
 * retry and changes no summary.
 */
public class BhavcopyHandler implements DatasetHandler {

  private final PinnedContracts contracts;
  private final SourceContract.Csv sourceContract;
  private final CsvCanonicalizer canonicalizer;
  private final DailyMarketSummaryMapper mapper;
  private final LoadManifest manifests;
  private final SourceObjectReader sources;
  private final CanonicalFileStore canonicalFiles;
  private final RejectedRecordStore rejectedRecords;
  private final JobCompletion completion;
  private final PublishDailyMarketSummaries publication;
  private final TransactionOperations transactions;

  /**
   * Creates the handler for one CSV dataset.
   *
   * @param contracts the dataset's source and mapping contracts, already validated
   * @throws IllegalArgumentException if the source contract does not read CSV, or the mapping
   *     contract does not produce daily market summaries
   */
  public BhavcopyHandler(
      PinnedContracts contracts,
      RuleRegistry rules,
      LoadManifest manifests,
      SourceObjectReader sources,
      CanonicalFileStore canonicalFiles,
      RejectedRecordStore rejectedRecords,
      JobCompletion completion,
      PublishDailyMarketSummaries publication,
      TransactionOperations transactions) {
    if (!(contracts.source() instanceof SourceContract.Csv csv)) {
      throw new IllegalArgumentException(
          contracts.source().id() + " does not read CSV, so this handler cannot process it");
    }
    this.contracts = contracts;
    this.sourceContract = csv;
    this.canonicalizer = new CsvCanonicalizer(csv, rules);
    this.mapper = new DailyMarketSummaryMapper(contracts.mapping());
    this.manifests = manifests;
    this.sources = sources;
    this.canonicalFiles = canonicalFiles;
    this.rejectedRecords = rejectedRecords;
    this.completion = completion;
    this.publication = publication;
    this.transactions = transactions;
  }

  @Override
  public DatasetUrn dataset() {
    return sourceContract.dataset();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException if the job was pinned to contracts this deployment does not hold;
   *     the attempt is retried, so a deployment that restores them can still process the job
   */
  @Override
  public void process(ClaimedJob job) {
    requireDeployedContracts(job.job().contracts());
    Manifest manifest = manifests.load(job);
    ManifestFile file = manifest.files().get(0);
    VerifiedSource source =
        switch (sources.read(file)) {
          case SourceRead.Verified verified -> verified.source();
          case SourceRead.ChecksumMismatch mismatch ->
              throw new PermanentFailureException(
                  SourceProblem.Code.CHECKSUM_MISMATCH.name(), mismatch.detail());
        };
    CanonicalRun run =
        new CanonicalRun(
            job.job().id(),
            job.attemptNumber(),
            TradeDate.parseIso(input(manifest, "tradeDate")).value(),
            file.bucket(),
            file.key(),
            sourceContract.id().name(),
            contracts.mapping().id().name());
    ExchangeName exchange = ExchangeName.of(input(manifest, "exchangeName"));
    List<DailyMarketSummary> accepted = new ArrayList<>();
    try (CanonicalFile canonical = canonicalFiles.create(run)) {
      RejectedRecordBuffer rejected =
          new RejectedRecordBuffer(
              (batchRun, runId, rows) ->
                  transactions.executeWithoutResult(
                      status -> rejectedRecords.saveAll(batchRun, runId, rows)),
              run,
              job.runId(),
              sourceContract.duplicateKey());
      CsvCanonicalizer.Outcome outcome =
          canonicalizer.canonicalize(
              source::open,
              row -> {
                canonical.append(row);
                rejected.add(row);
                mapper.map(run, exchange, row).ifPresent(accepted::add);
              });
      switch (outcome) {
        case CsvCanonicalizer.Outcome.Rejected rejection ->
            throw new PermanentFailureException(
                rejection.rejection().code().name(), rejection.rejection().detail());
        case CsvCanonicalizer.Outcome.Completed completed -> {
          rejected.flush();
          completion.recordCanonicalFile(job, canonical.commit());
          publication.publish(job, accepted, completed.counts().outcome(rejected.issueCount()));
        }
      }
    }
  }

  /** Fails the attempt if the job's pinned contracts are not the ones this handler holds. */
  private void requireDeployedContracts(PinnedContractVersions pinned) {
    requireSame(pinned.source(), pinned.sourceHash(), sourceContract.id(), contracts.sourceHash());
    requireSame(
        pinned.mapping(), pinned.mappingHash(), contracts.mapping().id(), contracts.mappingHash());
  }

  /**
   * Fails unless the pinned contract is the deployed one. The content hash catches a version that
   * was changed in place after the job was accepted (LLD section 6.2).
   */
  private static void requireSame(
      ContractId pinned, String pinnedHash, ContractId deployed, String deployedHash) {
    if (!pinned.equals(deployed)) {
      throw new IllegalStateException(
          "The job is pinned to " + pinned + ", but this deployment holds " + deployed);
    }
    if (!pinnedHash.equals(deployedHash)) {
      throw new IllegalStateException(
          "The job is pinned to "
              + pinned
              + " with hash "
              + pinnedHash
              + ", but this deployment's copy has hash "
              + deployedHash);
    }
  }

  /** Returns a manifest input; the manifest was checked to carry the submission's inputs. */
  private static String input(Manifest manifest, String name) {
    String value = manifest.inputs().get(name);
    if (value == null) {
      throw new IllegalStateException("The checked manifest has no input " + name);
    }
    return value;
  }
}
