package com.bondplatform.dataprocessing.pipeline.application;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.JsonDocumentReader;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalizer;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonRejection;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.job.application.DatasetHandler;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.mapping.domain.CollateralStatusRule;
import com.bondplatform.dataprocessing.mapping.domain.CollectionEntryMapper;
import com.bondplatform.dataprocessing.mapping.domain.JsonRequestStatus;
import com.bondplatform.dataprocessing.mapping.domain.ScalarPrecedenceResolver;
import com.bondplatform.dataprocessing.mapping.domain.SecurityFieldMapper;
import com.bondplatform.dataprocessing.publication.application.PublishSecurityDetails;
import com.bondplatform.dataprocessing.publication.domain.JsonFileCounts;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetails;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.LoadManifest;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import com.bondplatform.dataprocessing.source.domain.VerifiedSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Processes an NSDL security-details job from its manifest to the published security (LLD section
 * 13.7).
 *
 * <p>An attempt loads the manifest, whose files all name the requested ISIN, and runs stage 1 on
 * each file. A malformed file, or one whose content does not match the manifest's checksum, is
 * skipped with an error and the others continue (LLD section 19). In each read file, coverage and
 * assets supplied beside its own {@code Unsecured} status are ignored. The winning scalars are
 * chosen by S3 object precedence and mapped with the request's collection entries; a winning {@code
 * Unsecured} status then clears coverage and drops assets. The rejected parts are stored in their
 * own transaction and the canonical file is stored and recorded on the run before the details and
 * the job's outcome are published in one transaction.
 *
 * <p>Warnings, such as a payload ISIN that differs from the file name's, are only logged with the
 * job ID; they are not errors and are never shown by the API (LLD section 16).
 */
public class NsdlHandler implements DatasetHandler {

  /** The manifest input naming the requested ISIN. */
  static final String ISIN_INPUT = "isin_code";

  private static final Logger LOG = LoggerFactory.getLogger(NsdlHandler.class);

  private final PinnedContracts contracts;
  private final SourceContract.Json sourceContract;
  private final JsonCanonicalizer canonicalizer;
  private final CollateralStatusRule collateral;
  private final SecurityFieldMapper fields;
  private final CollectionEntryMapper entries;
  private final LoadManifest manifests;
  private final SourceObjectReader sources;
  private final JsonDocumentReader documents;
  private final CanonicalFileStore canonicalFiles;
  private final JsonRejectionStore rejections;
  private final JobCompletion completion;
  private final PublishSecurityDetails publication;
  private final TransactionOperations transactions;

  /**
   * Creates the handler for one JSON dataset.
   *
   * @param contracts the dataset's source and mapping contracts, already validated
   * @throws IllegalArgumentException if the source contract does not read JSON, or the mapping
   *     contract does not map security details
   */
  public NsdlHandler(
      PinnedContracts contracts,
      RuleRegistry rules,
      LoadManifest manifests,
      SourceObjectReader sources,
      JsonDocumentReader documents,
      CanonicalFileStore canonicalFiles,
      JsonRejectionStore rejections,
      JobCompletion completion,
      PublishSecurityDetails publication,
      TransactionOperations transactions) {
    if (!(contracts.source() instanceof SourceContract.Json json)) {
      throw new IllegalArgumentException(
          contracts.source().id() + " does not read JSON, so this handler cannot process it");
    }
    this.contracts = contracts;
    this.sourceContract = json;
    this.canonicalizer = new JsonCanonicalizer(json, rules);
    this.collateral = new CollateralStatusRule(contracts.mapping());
    this.fields = new SecurityFieldMapper(contracts.mapping());
    this.entries = new CollectionEntryMapper(contracts.mapping());
    this.manifests = manifests;
    this.sources = sources;
    this.documents = documents;
    this.canonicalFiles = canonicalFiles;
    this.rejections = rejections;
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
   * @throws IllegalStateException if the job was pinned to contracts this deployment does not hold,
   *     or S3 gave a source object no {@code LastModified}; the attempt is retried
   */
  @Override
  public void process(ClaimedJob job) {
    DeployedContracts.require(contracts, job.job().contracts());
    Manifest manifest = manifests.load(job);
    JobId jobId = job.job().id();
    Isin isin = Isin.of(DeployedContracts.input(manifest, ISIN_INPUT));
    List<FileResult> files = new ArrayList<>();
    for (ManifestFile file : manifest.files()) {
      files.add(
          switch (sources.read(file)) {
            case SourceRead.Verified verified -> read(jobId, isin, verified.source());
            case SourceRead.ChecksumMismatch mismatch ->
                skipped(
                    file,
                    new ValidationIssue(ErrorCode.CHECKSUM_MISMATCH, mismatch.detail() + "."));
          });
    }
    List<JsonFileEvidence> evidence = files.stream().map(FileResult::evidence).toList();
    JsonCanonicalRun run =
        new JsonCanonicalRun(
            jobId,
            job.attemptNumber(),
            isin,
            sourceContract.id().name(),
            contracts.mapping().id().name());
    long errorCount =
        Objects.requireNonNull(
            transactions.execute(status -> rejections.saveJson(run, job.runId(), evidence)));
    completion.recordCanonicalFile(job, canonicalFiles.storeJson(run, evidence));

    List<ScalarPrecedenceResolver.ScalarSource> scalarSources = new ArrayList<>();
    List<CollectionEntryMapper.EntrySource> entrySources = new ArrayList<>();
    for (FileResult file : files) {
      if (file.used() instanceof JsonFileCanonical.Read read) {
        scalarSources.add(
            new ScalarPrecedenceResolver.ScalarSource(
                file.key(), Objects.requireNonNull(file.lastModified()), read.scalars()));
        entrySources.add(new CollectionEntryMapper.EntrySource(file.key(), read.entries()));
      }
    }
    CollateralStatusRule.Applied applied =
        collateral.apply(
            fields.map(jobId, ScalarPrecedenceResolver.resolve(scalarSources)),
            entries.map(isin, jobId, entrySources));
    JobStatus status =
        JsonRequestStatus.of(files.stream().map(FileResult::used).toList(), errorCount);
    publication.publish(
        job,
        new SecurityDetails(isin, applied.scalars(), applied.collections()),
        counts(files, evidence),
        status,
        Math.toIntExact(errorCount));
  }

  /** A file that could not be read is skipped with one error; the others continue. */
  private static FileResult skipped(ManifestFile file, ValidationIssue issue) {
    JsonFileCanonical skipped = new JsonFileCanonical.Skipped(issue);
    return new FileResult(
        file, null, new JsonFileEvidence(file.bucket(), file.key(), skipped, List.of()), skipped);
  }

  /**
   * Runs stage 1 on one file and removes what its own {@code Unsecured} status makes inapplicable;
   * the file name was checked to name the requested ISIN.
   */
  private FileResult read(JobId jobId, Isin isin, VerifiedSource source) {
    ManifestFile file = source.file();
    JsonFileCanonical canonical = canonicalizer.canonicalize(isin, documents.read(bytes(source)));
    if (!(canonical instanceof JsonFileCanonical.Read read)) {
      return new FileResult(
          file,
          null,
          new JsonFileEvidence(file.bucket(), file.key(), canonical, List.of()),
          canonical);
    }
    Instant lastModified = lastModified(source);
    read.warnings()
        .forEach(
            warning ->
                LOG.warn(
                    "Source file warning, jobId={}, file={}: {}",
                    jobId.value(),
                    file.fileName(),
                    warning));
    CollateralStatusRule.Screened screened = collateral.screen(read.scalars(), read.entries());
    List<JsonRejection.ValueIgnored> ignored =
        screened.conflicts().stream()
            .map(
                conflict ->
                    new JsonRejection.ValueIgnored(
                        conflict.path(),
                        conflict.rawValue(),
                        conflict.issue(),
                        conflict.actionTaken()))
            .toList();
    return new FileResult(
        file,
        lastModified,
        new JsonFileEvidence(file.bucket(), file.key(), canonical, ignored),
        new JsonFileCanonical.Read(
            screened.scalars(), screened.entries(), read.structureIssues(), read.warnings()));
  }

  private static JsonFileCounts counts(List<FileResult> files, List<JsonFileEvidence> evidence) {
    int processed =
        (int) files.stream().filter(file -> file.used() instanceof JsonFileCanonical.Read).count();
    return new JsonFileCounts(
        files.size(),
        processed,
        files.size() - processed,
        evidence.stream().mapToInt(JsonFileEvidence::rejectedFieldCount).sum());
  }

  private static Instant lastModified(VerifiedSource source) {
    return source
        .lastModified()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "S3 gave no LastModified for " + source.file().fileName()));
  }

  private static byte[] bytes(VerifiedSource source) {
    try (InputStream in = source.open()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One file's outcome.
   *
   * @param file the listed file
   * @param lastModified the object's S3 {@code LastModified}, for a file that was read
   * @param evidence stage 1's result and the values ignored, as stored
   * @param used stage 1's result without the ignored values, which stage 2 maps
   */
  private record FileResult(
      ManifestFile file,
      @Nullable Instant lastModified,
      JsonFileEvidence evidence,
      JsonFileCanonical used) {

    String key() {
      return file.key();
    }
  }
}
