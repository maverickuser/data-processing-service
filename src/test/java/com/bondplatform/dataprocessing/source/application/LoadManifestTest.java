package com.bondplatform.dataprocessing.source.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.admission.domain.CanonicalJson;
import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.application.TemporaryFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.adapter.json.StrictJsonObjectParser;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.Manifests;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Loading a job's manifest, with an in-memory object store and file record. */
class LoadManifestTest {

  private static final JobId JOB =
      new JobId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
  private static final ManifestLocation LOCATION =
      new ManifestLocation("data-fetch-service-artifacts", "runs/run_202/manifest.json", null);
  private static final ContractRegistry CONTRACTS =
      new ClasspathContractCatalog(
              new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
          .load(
              List.of(
                  new ContractPair("bse-debt-bhavcopy-csv-v1", "bse-debt-bhavcopy-mapping-v1"),
                  new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1")));

  private final List<Long> requestedLimits = new ArrayList<>();
  private final List<List<ManifestFile>> saved = new ArrayList<>();
  private byte[] stored = bytes(Manifests.nsdl("INE121A07QY9_isin-details.json"));
  private final LoadManifest loadManifest =
      new LoadManifest(
          (location, maxBytes) -> {
            assertThat(location).isEqualTo(LOCATION);
            requestedLimits.add(maxBytes);
            return stored;
          },
          new StrictJsonObjectParser(),
          (jobId, files) -> {
            assertThat(jobId).isEqualTo(JOB);
            saved.add(files);
          },
          CONTRACTS,
          new DirectTransactions());

  @Test
  void returnsTheCheckedManifestAndRecordsItsFiles() {
    Manifest manifest = loadManifest.load(nsdlJob());

    assertThat(manifest.runId()).isEqualTo("run_202");
    assertThat(manifest.files())
        .extracting(ManifestFile::fileName)
        .containsExactly("INE121A07QY9_isin-details.json");
    assertThat(saved).containsExactly(manifest.files());
    assertThat(requestedLimits).containsExactly(1_048_576L);
  }

  @Test
  void documentThatIsNotOneJsonObjectIsInvalidManifest() {
    stored = "[1, 2]".getBytes(StandardCharsets.UTF_8);

    assertFailsPermanently("INVALID_MANIFEST", "not one JSON object");
    assertThat(saved).isEmpty();
  }

  @Test
  void unusableManifestNamesItsFirstProblems() {
    stored =
        bytes(
            with(
                manifest -> {
                  for (String name :
                      List.of("specversion", "id", "source", "type", "dataschema", "subject")) {
                    manifest.remove(name);
                  }
                }));

    assertFailsPermanently("INVALID_MANIFEST", "/specversion is required")
        .hasMessageContaining("and 1 more");
  }

  // U-JOB-03
  @Test
  void manifestOfAnotherRunIsInvalidManifestNamingTheField() {
    stored = bytes(with(manifest -> Manifests.data(manifest).put("run_id", "run_999")));

    assertFailsPermanently("INVALID_MANIFEST", "data.run_id");
    assertThat(saved).isEmpty();
  }

  @Test
  void manifestListingCsvForJsonDatasetIsInvalidManifest() {
    stored =
        bytes(
            with(
                manifest ->
                    Manifests.data(manifest)
                        .put(
                            "files",
                            List.of(
                                Manifests.file("x", "runs/run_202/raw/x/1/a.csv", "csv", 10)))));

    assertFailsPermanently("INVALID_MANIFEST", "Every listed file must be JSON");
  }

  // U-SRC-02, the listed sizes
  @Test
  void listedFilesOverTheLimitAreTooLarge() {
    stored = bytes(with(manifest -> Manifests.firstFile(manifest).put("size_bytes", 10_485_761L)));

    assertFailsPermanently("SOURCE_TOO_LARGE", "10485760");
  }

  @Test
  void bseJobIsCheckedAgainstTheCsvContract() {
    stored = bytes(Manifests.bse());
    StoredJob bse =
        job(
            "urn:bond-platform:dataset:bse-debt-trades",
            "exchange/BSE/trade-date/2026-09-21",
            "daily-bhavcopy",
            "run_101",
            "{\"exchangeName\": \"BSE\", \"tradeDate\": \"2026-09-21\"}");

    assertThat(loadManifest.load(new ClaimedJob(bse, UUID.randomUUID(), 1)).files()).hasSize(1);
  }

  // U-JSON-05 through the use case
  @Test
  void fileNamesOfAnotherSecurityFailTheJobBeforeAnythingIsRecorded() {
    stored =
        bytes(
            with(
                manifest ->
                    Manifests.data(manifest)
                        .put(
                            "files",
                            List.of(
                                Manifests.file(
                                    "ratings",
                                    "runs/run_202/raw/ratings/1/INE002A08534_ratings.json",
                                    "json",
                                    10)))));

    assertFailsPermanently("INVALID_SOURCE_FILENAME", "INE002A08534");
    assertThat(saved).isEmpty();
  }

  // U-SRC-04 through the use case
  @Test
  void bhavcopyOfAnotherDayIsTradeDateMismatch() {
    Map<String, Object> manifest = Manifests.bse();
    Manifests.firstFile(manifest)
        .put("key", "runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup22092026.csv");
    stored = bytes(manifest);
    StoredJob bse =
        job(
            "urn:bond-platform:dataset:bse-debt-trades",
            "exchange/BSE/trade-date/2026-09-21",
            "daily-bhavcopy",
            "run_101",
            "{\"exchangeName\": \"BSE\", \"tradeDate\": \"2026-09-21\"}");

    assertThatThrownBy(() -> loadManifest.load(new ClaimedJob(bse, UUID.randomUUID(), 1)))
        .isInstanceOfSatisfying(
            PermanentFailureException.class,
            failure -> assertThat(failure.code()).isEqualTo("TRADE_DATE_MISMATCH"));
  }

  @Test
  void failuresOfTheStoreReachTheCallerUnchanged() {
    LoadManifest unavailable =
        new LoadManifest(
            (location, maxBytes) -> {
              throw new TemporaryFailureException(
                  "SOURCE_UNAVAILABLE", "S3 did not answer", new IllegalStateException());
            },
            new StrictJsonObjectParser(),
            (jobId, files) -> saved.add(files),
            CONTRACTS,
            new DirectTransactions());

    assertThatThrownBy(() -> unavailable.load(nsdlJob()))
        .isInstanceOf(TemporaryFailureException.class);
    assertThat(saved).isEmpty();
  }

  private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
      assertFailsPermanently(String code, String detail) {
    return assertThatThrownBy(() -> loadManifest.load(nsdlJob()))
        .isInstanceOfSatisfying(
            PermanentFailureException.class, failure -> assertThat(failure.code()).isEqualTo(code))
        .hasMessageContaining(detail);
  }

  private static ClaimedJob nsdlJob() {
    return new ClaimedJob(
        job(
            "urn:bond-platform:dataset:nsdl-security",
            "isin/INE121A07QY9",
            "nsdl-bond-data",
            "run_202",
            "{\"isin_code\": \"INE121A07QY9\"}"),
        UUID.randomUUID(),
        1);
  }

  private static StoredJob job(
      String dataset, String subject, String eventType, String runId, String inputs) {
    return new StoredJob(
        JOB,
        JobStatus.PROCESSING,
        1,
        new DatasetUrn(dataset),
        subject,
        eventType,
        "evt_1",
        runId,
        new OrderingGroup("g"),
        inputs,
        LOCATION,
        "sha256:" + "b".repeat(64),
        new PinnedContractVersions(
            new ContractId("s", "v1"), "sha256:s", new ContractId("m", "v1"), "sha256:m"));
  }

  private static Map<String, Object> with(Consumer<Map<String, Object>> change) {
    Map<String, Object> manifest = Manifests.nsdl("INE121A07QY9_isin-details.json");
    change.accept(manifest);
    return manifest;
  }

  private static byte[] bytes(Map<String, Object> manifest) {
    return CanonicalJson.of(manifest).getBytes(StandardCharsets.UTF_8);
  }

  /** Runs callbacks directly, standing in for a transaction manager. */
  private static final class DirectTransactions implements TransactionOperations {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return action.doInTransaction(new SimpleTransactionStatus());
    }
  }
}
