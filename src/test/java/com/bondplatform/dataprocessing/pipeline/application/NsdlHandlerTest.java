package com.bondplatform.dataprocessing.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.canonical.adapter.json.StrictJsonReader;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.JsonRejectionStore;
import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonRejection;
import com.bondplatform.dataprocessing.canonical.domain.NsdlContract;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.publication.application.PublishSecurityDetails;
import com.bondplatform.dataprocessing.publication.domain.JsonFileCounts;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetails;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.source.application.LoadManifest;
import com.bondplatform.dataprocessing.source.application.SourceObjectReader;
import com.bondplatform.dataprocessing.source.domain.Manifest;
import com.bondplatform.dataprocessing.source.domain.ManifestFile;
import com.bondplatform.dataprocessing.source.domain.SourceFormat;
import com.bondplatform.dataprocessing.source.domain.SourceRead;
import com.bondplatform.dataprocessing.source.domain.VerifiedSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class NsdlHandlerTest {

  private static final PinnedContracts CONTRACTS = NsdlContract.PINNED;
  private static final String ISIN = "INE831R08076";
  private static final JobId JOB =
      new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000002"));
  private static final UUID RUN = UUID.fromString("0190f3a0-0000-7000-8000-0000000000bb");
  private static final Instant EARLY = Instant.parse("2026-10-05T09:00:00Z");
  private static final Instant LATE = Instant.parse("2026-10-05T09:05:00Z");
  private static final String CANONICAL_KEY =
      "canonical/" + JOB.value() + "/attempt-1/canonical.jsonl";

  private final LoadManifest manifests = mock(LoadManifest.class);
  private final SourceObjectReader sources = mock(SourceObjectReader.class);
  private final CanonicalFileStore canonicalFiles = mock(CanonicalFileStore.class);
  private final JsonRejectionStore rejections = mock(JsonRejectionStore.class);
  private final JobCompletion completion = mock(JobCompletion.class);
  private final PublishSecurityDetails publication = mock(PublishSecurityDetails.class);
  private final NsdlHandler handler = handler(CONTRACTS);
  private final ClaimedJob job = claimed(pinnedVersions());
  private final List<ManifestFile> listed = new ArrayList<>();

  @Test
  void handlesTheNsdlDataset() {
    assertThat(handler.dataset()).isEqualTo(CONTRACTS.source().dataset());
  }

  @Test
  void rejectsContractsThatDoNotReadJson() {
    assertThatThrownBy(() -> handler(BhavcopyContract.PINNED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not read JSON");
  }

  // I-JSON-01 at the handler: every sample is read, evidence stored, then details published
  @Test
  void readsEveryFileStoresEvidenceThenPublishesDetails() {
    givenSample("isin-details", EARLY);
    givenSample("coupon-details", EARLY);
    givenSample("listings", EARLY);
    givenStored(0);

    handler.process(job);

    InOrder order = inOrder(rejections, canonicalFiles, completion, publication);
    order.verify(rejections).saveJson(eq(run()), eq(RUN), any());
    order.verify(canonicalFiles).storeJson(eq(run()), any());
    order.verify(completion).recordCanonicalFile(job, CANONICAL_KEY);
    order.verify(publication).publish(eq(job), any(), any(), any(), anyInt());
    assertThat(storedEvidence())
        .extracting(JsonFileEvidence::key)
        .containsExactlyElementsOf(listed.stream().map(ManifestFile::key).toList());
    Published published = published();
    assertThat(published.status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(published.errorCount()).isZero();
    assertThat(published.counts()).isEqualTo(new JsonFileCounts(3, 3, 0, 0));
    assertThat(published.details().isin().value()).isEqualTo(ISIN);
    assertThat(text(published.details().scalars(), "couponType")).isEqualTo("Simple");
    assertThat(
            Objects.requireNonNull(published.details().scalars().fields().get("couponType"))
                .source()
                .sourceFile())
        .isEqualTo(ISIN + "_coupon-details.json");
    assertThat(published.details().collections().listings()).isNotEmpty();
  }

  // I-JSON-01 at the handler: the golden manifest's exact collections; redemptions adds nothing
  @Test
  void goldenManifestMapsEveryCollectionAndRedemptionsAddsNothing() {
    for (String suffix :
        List.of(
            "isin-details",
            "instrument-details",
            "coupon-details",
            "credit-ratings",
            "listings",
            "redemptions")) {
      givenSample(suffix, EARLY);
    }
    givenStored(0);

    handler.process(job);

    SecurityDetails details = published().details();
    assertThat(details.collections().cashFlows()).hasSize(11);
    assertThat(details.collections().listings()).hasSize(2);
    assertThat(details.collections().ratings()).hasSize(15);
    assertThat(details.collections().collateralAssets()).isEmpty();
    assertThat(details.scalars().fields().get("redemptionDate"))
        .extracting(SecurityScalars.Field::value)
        .isEqualTo(new SecurityValue.Date(LocalDate.of(2029, 6, 8)));
    List<String> sourceFiles = new ArrayList<>();
    details
        .scalars()
        .fields()
        .values()
        .forEach(field -> sourceFiles.add(field.source().sourceFile()));
    List.of(
            details.collections().cashFlows(),
            details.collections().listings(),
            details.collections().ratings())
        .forEach(entries -> entries.forEach(entry -> sourceFiles.add(entry.source().sourceFile())));
    assertThat(sourceFiles).isNotEmpty().noneMatch(file -> file.endsWith("_redemptions.json"));
  }

  // I-JSON-04 at the handler: a malformed file is skipped and the others still publish
  @Test
  void malformedFileIsSkippedAndOthersPublish() {
    givenSample("coupon-details", EARLY);
    given("isin-details", "{\"issuerName\": ", EARLY);
    givenStored(1);

    handler.process(job);

    assertThat(storedEvidence().get(1).canonical()).isInstanceOf(JsonFileCanonical.Skipped.class);
    Published published = published();
    assertThat(published.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(published.errorCount()).isEqualTo(1);
    assertThat(published.counts()).isEqualTo(new JsonFileCounts(2, 1, 1, 0));
  }

  @Test
  void requestWithoutUsableDataFails() {
    given("isin-details", "[", EARLY);
    given("coupon-details", "{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"-\"}}}", EARLY);
    givenStored(1);

    handler.process(job);

    Published published = published();
    assertThat(published.status()).isEqualTo(JobStatus.FAILED);
    assertThat(published.counts()).isEqualTo(new JsonFileCounts(2, 1, 1, 0));
  }

  // Scalar precedence by S3 LastModified, whatever the manifest order
  @Test
  void latestFileWinsCompetingScalars() {
    given("coupon-details", coupon("Compound"), LATE);
    given("instrument-details", coupon("Simple"), EARLY);
    givenStored(0);

    handler.process(job);

    SecurityScalars.Field winner = published().details().scalars().fields().get("couponType");
    assertThat(Objects.requireNonNull(winner).value())
        .isEqualTo(new SecurityValue.Text("Compound"));
    assertThat(winner.source().sourceFile()).isEqualTo(ISIN + "_coupon-details.json");
  }

  // I-JSON-07 at the handler: coverage beside the file's own Unsecured is ignored and recorded
  @Test
  void coverageBesideUnsecuredIsIgnoredRecordedAndCleared() {
    given(
        "instrument-details",
        """
        {"instrumentsVo": {"assetCover": {"securedFlag": "UNSECURED", "assetCvrPercent": 100}}}
        """,
        EARLY);
    givenStored(1);

    handler.process(job);

    assertThat(storedEvidence().getFirst().rejections())
        .singleElement()
        .satisfies(
            rejection -> {
              assertThat(rejection).isInstanceOf(JsonRejection.ValueIgnored.class);
              assertThat(rejection.path()).isEqualTo("$.instrumentsVo.assetCover.assetCvrPercent");
            });
    SecurityDetails details = published().details();
    assertThat(text(details.scalars(), "collateralStatus")).isEqualTo("UNSECURED");
    assertThat(details.scalars().fields()).doesNotContainKey("assetCoverage");
    assertThat(details.scalars().cleared()).contains("assetCoverage", "assetCoverageBasis");
  }

  // U-JSON-06, AF-1: a payload ISIN mismatch is a warning, not an error
  @Test
  void payloadIsinMismatchIsNoError() {
    given("isin-details", "{\"isin\": \"INE0O7U07046\", \"issuerName\": \"Acme\"}", EARLY);
    givenStored(0);

    handler.process(job);

    JsonFileCanonical.Read read = (JsonFileCanonical.Read) storedEvidence().getFirst().canonical();
    assertThat(read.warnings()).isNotEmpty();
    assertThat(storedEvidence().getFirst().rejections()).isEmpty();
    assertThat(published().status()).isEqualTo(JobStatus.COMPLETED);
  }

  // I-JSON-04, LLD 19: only the mismatching file is skipped
  @Test
  void checksumMismatchSkipsOnlyThatFile() {
    givenSample("coupon-details", EARLY);
    ManifestFile mismatching = file("listings");
    listed.add(mismatching);
    when(manifests.load(job)).thenReturn(manifest());
    when(sources.read(mismatching))
        .thenReturn(new SourceRead.ChecksumMismatch(mismatching, "The sha256 differs"));
    givenStored(1);

    handler.process(job);

    assertThat(storedEvidence().get(1).canonical())
        .isEqualTo(
            new JsonFileCanonical.Skipped(
                new ValidationIssue(ErrorCode.CHECKSUM_MISMATCH, "The sha256 differs.")));
    Published published = published();
    assertThat(published.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(published.counts()).isEqualTo(new JsonFileCounts(2, 1, 1, 0));
  }

  @Test
  void sourceWithoutLastModifiedIsDefect() {
    ManifestFile file = file("isin-details");
    when(manifests.load(job)).thenReturn(manifest());
    when(sources.read(file))
        .thenReturn(
            new SourceRead.Verified(new VerifiedSource(file, sample("isin-details"), null, null)));
    givenStored(0);

    assertThatThrownBy(() -> handler.process(job))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ISIN + "_isin-details.json");
    verifyNoInteractions(publication);
  }

  @Test
  void jobPinnedToOtherContractsIsNotProcessed() {
    ClaimedJob other =
        claimed(
            new PinnedContractVersions(
                BhavcopyContract.PINNED.source().id(),
                CONTRACTS.sourceHash(),
                CONTRACTS.mapping().id(),
                CONTRACTS.mappingHash()));

    assertThatThrownBy(() -> handler.process(other)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(manifests, sources, publication);
  }

  private NsdlHandler handler(PinnedContracts contracts) {
    return new NsdlHandler(
        contracts,
        RuleRegistry.standard(),
        manifests,
        sources,
        new StrictJsonReader(),
        canonicalFiles,
        rejections,
        completion,
        publication,
        TransactionOperations.withoutTransaction());
  }

  private void givenSample(String suffix, Instant lastModified) {
    given(suffix, sample(suffix), lastModified);
  }

  private void given(String suffix, String json, Instant lastModified) {
    given(suffix, json.getBytes(StandardCharsets.UTF_8), lastModified);
  }

  private void given(String suffix, byte[] content, Instant lastModified) {
    ManifestFile file = file(suffix);
    listed.add(file);
    when(manifests.load(job)).thenReturn(manifest());
    when(sources.read(file))
        .thenReturn(new SourceRead.Verified(new VerifiedSource(file, content, lastModified, null)));
  }

  private void givenStored(long errorCount) {
    when(rejections.saveJson(any(), any(), any())).thenReturn(errorCount);
    when(canonicalFiles.storeJson(any(), any())).thenReturn(CANONICAL_KEY);
  }

  private List<JsonFileEvidence> storedEvidence() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<JsonFileEvidence>> evidence = ArgumentCaptor.forClass(List.class);
    verify(rejections).saveJson(eq(run()), eq(RUN), evidence.capture());
    return evidence.getValue();
  }

  private Published published() {
    ArgumentCaptor<SecurityDetails> details = ArgumentCaptor.forClass(SecurityDetails.class);
    ArgumentCaptor<JsonFileCounts> counts = ArgumentCaptor.forClass(JsonFileCounts.class);
    ArgumentCaptor<JobStatus> status = ArgumentCaptor.forClass(JobStatus.class);
    ArgumentCaptor<Integer> errors = ArgumentCaptor.forClass(Integer.class);
    verify(publication)
        .publish(eq(job), details.capture(), counts.capture(), status.capture(), errors.capture());
    return new Published(
        details.getValue(), counts.getValue(), status.getValue(), errors.getValue());
  }

  private record Published(
      SecurityDetails details, JsonFileCounts counts, JobStatus status, int errorCount) {}

  private static String text(SecurityScalars scalars, String field) {
    SecurityScalars.Field value = Objects.requireNonNull(scalars.fields().get(field), field);
    return ((SecurityValue.Text) value.value()).value();
  }

  private static String coupon(String type) {
    return "{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"" + type + "\"}}}";
  }

  private static JsonCanonicalRun run() {
    return new JsonCanonicalRun(
        JOB, 1, Isin.of(ISIN), CONTRACTS.source().id().name(), CONTRACTS.mapping().id().name());
  }

  private Manifest manifest() {
    return new Manifest(
        "manifest-2",
        CONTRACTS.source().dataset(),
        "isin/" + ISIN,
        "nsdl-bond-data",
        "evt_2",
        "run_201",
        Map.of(NsdlHandler.ISIN_INPUT, ISIN),
        "sha256:" + "c".repeat(64),
        listed.isEmpty() ? List.of(file("isin-details")) : List.copyOf(listed));
  }

  private static ManifestFile file(String suffix) {
    return new ManifestFile(
        suffix,
        "data-fetch-service-artifacts",
        "runs/run_201/raw/" + suffix + "/1/" + ISIN + "_" + suffix + ".json",
        SourceFormat.JSON,
        "a".repeat(64),
        1024,
        null);
  }

  private static byte[] sample(String suffix) {
    String name = "/fixtures/nsdl/" + ISIN + "_" + suffix + ".json";
    try (InputStream in = Objects.requireNonNull(NsdlHandlerTest.class.getResourceAsStream(name))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static PinnedContractVersions pinnedVersions() {
    return new PinnedContractVersions(
        CONTRACTS.source().id(),
        CONTRACTS.sourceHash(),
        CONTRACTS.mapping().id(),
        CONTRACTS.mappingHash());
  }

  private static ClaimedJob claimed(PinnedContractVersions versions) {
    StoredJob job =
        new StoredJob(
            JOB,
            JobStatus.PROCESSING,
            1,
            CONTRACTS.source().dataset(),
            "isin/" + ISIN,
            "nsdl-bond-data",
            "evt_2",
            "run_201",
            new OrderingGroup(ISIN),
            "{\"isin_code\":\"" + ISIN + "\"}",
            new ManifestLocation(
                "data-fetch-service-artifacts", "runs/run_201/manifest.json", null),
            "sha256:" + "c".repeat(64),
            versions);
    return new ClaimedJob(job, RUN, 1);
  }
}
