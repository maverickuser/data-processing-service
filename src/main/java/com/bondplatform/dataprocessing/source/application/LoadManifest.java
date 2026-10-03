package com.bondplatform.dataprocessing.source.application;

import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.source.domain.FilenameCheck;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestReader;
import com.bondplatform.dataprocessing.source.domain.ManifestVerifier;
import com.bondplatform.dataprocessing.source.domain.ManifestVerifier.SubmittedRun;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import com.bondplatform.dataprocessing.source.domain.SourceProblem;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Loads a job's manifest at the start of an attempt (LLD sections 2.1, 13.1, 19): reads it, checks
 * it, and records the files it lists.
 *
 * <p>Every problem with the manifest fails the job without a retry, because the manifest is an
 * immutable object: {@code INVALID_MANIFEST} for one that is malformed or disagrees with the
 * submission, {@code SOURCE_TOO_LARGE} for one over its limits, {@code INVALID_SOURCE_FILENAME} or
 * {@code TRADE_DATE_MISMATCH} for file names that do not agree with its inputs. A retried attempt
 * reads the same manifest and records nothing twice.
 */
@Service
public class LoadManifest {

  private static final int PROBLEMS_SHOWN = 5;

  private final ManifestSource source;
  private final JsonObjectParser json;
  private final SourceFileRepository sourceFiles;
  private final ContractRegistry contracts;
  private final TransactionOperations transactions;

  /** Creates the use case. */
  public LoadManifest(
      ManifestSource source,
      JsonObjectParser json,
      SourceFileRepository sourceFiles,
      ContractRegistry contracts,
      TransactionOperations transactions) {
    this.source = source;
    this.json = json;
    this.sourceFiles = sourceFiles;
    this.contracts = contracts;
    this.transactions = transactions;
  }

  /**
   * Returns the job's checked manifest, with its files recorded.
   *
   * @throws PermanentFailureException if the manifest cannot be used
   * @throws com.bondplatform.dataprocessing.job.application.TemporaryFailureException if storage
   *     could not be read for a reason that may pass
   */
  public Manifest load(ClaimedJob claimed) {
    StoredJob job = claimed.job();
    byte[] document = source.read(job.manifest(), Manifest.MAX_BYTES);
    Map<String, Object> event =
        json.parse(document).orElseThrow(() -> invalid("The manifest is not one JSON object"));
    Manifest manifest =
        switch (ManifestReader.read(event)) {
          case ManifestReader.Reading.Read read -> read.manifest();
          case ManifestReader.Reading.Unreadable unreadable ->
              throw invalid("The manifest is not usable: " + summary(unreadable.problems()));
        };
    SourceFormat format = formatOf(job);
    Optional<SourceProblem> problem =
        ManifestVerifier.verify(manifest, submittedRun(job), format)
            .or(() -> namesProblem(manifest, format));
    if (problem.isPresent()) {
      throw new PermanentFailureException(problem.get().code().name(), problem.get().detail());
    }
    transactions.executeWithoutResult(status -> sourceFiles.saveAll(job.id(), manifest.files()));
    return manifest;
  }

  /** Checks that the files' names agree with the manifest's inputs. */
  private static Optional<SourceProblem> namesProblem(Manifest manifest, SourceFormat format) {
    return switch (format) {
      case CSV -> FilenameCheck.csv(manifest.files().get(0), manifest.inputs());
      case JSON -> FilenameCheck.json(manifest.files(), manifest.inputs());
    };
  }

  private SubmittedRun submittedRun(StoredJob job) {
    return new SubmittedRun(
        job.dataset(),
        job.subject(),
        job.eventType(),
        job.fetchEventId(),
        job.runId(),
        textValues(json.parse(job.inputsJson().getBytes(StandardCharsets.UTF_8))),
        job.datasetFingerprint());
  }

  /**
   * Returns the format the dataset's source contract reads. The registry holds one version per
   * dataset rather than the version pinned at admission, which is enough here: the format belongs
   * to the dataset, since an incompatible contract needs a new dataset URN (LLD section 2.1).
   */
  private SourceFormat formatOf(StoredJob job) {
    return switch (contracts.contractsFor(job.dataset()).source()) {
      case SourceContract.Csv csv -> SourceFormat.CSV;
      case SourceContract.Json jsonContract -> SourceFormat.JSON;
    };
  }

  /** Returns the stored inputs as text values; admission accepted only text. */
  private static Map<String, String> textValues(Optional<Map<String, Object>> inputs) {
    Map<String, String> texts = new LinkedHashMap<>();
    inputs
        .orElseThrow(() -> new IllegalStateException("Stored inputs are not a JSON object"))
        .forEach((name, value) -> texts.put(name, String.valueOf(value)));
    return texts;
  }

  private static String summary(List<String> problems) {
    String shown =
        String.join("; ", problems.subList(0, Math.min(PROBLEMS_SHOWN, problems.size())));
    return problems.size() > PROBLEMS_SHOWN
        ? shown + "; and " + (problems.size() - PROBLEMS_SHOWN) + " more"
        : shown;
  }

  private static PermanentFailureException invalid(String detail) {
    return new PermanentFailureException(SourceProblem.Code.INVALID_MANIFEST.name(), detail);
  }
}
