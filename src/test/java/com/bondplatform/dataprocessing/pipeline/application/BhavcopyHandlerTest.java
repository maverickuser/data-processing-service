package com.bondplatform.dataprocessing.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.canonical.application.CanonicalFile;
import com.bondplatform.dataprocessing.canonical.application.CanonicalFileStore;
import com.bondplatform.dataprocessing.canonical.application.RejectedRecordStore;
import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.application.PermanentFailureException;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.job.domain.StoredJob;
import com.bondplatform.dataprocessing.publication.application.PublishDailyMarketSummaries;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class BhavcopyHandlerTest {

  private static final PinnedContracts CONTRACTS = BhavcopyContract.PINNED;
  private static final JobId JOB =
      new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001"));
  private static final UUID RUN = UUID.fromString("0190f3a0-0000-7000-8000-0000000000aa");
  private static final ManifestFile FILE =
      new ManifestFile(
          "debt-bhavcopy",
          "data-fetch-service-artifacts",
          "runs/run_101/raw/debt-bhavcopy/1/BSE_fgroup01012026.csv",
          SourceFormat.CSV,
          "a".repeat(64),
          1024,
          null);
  private static final String CANONICAL_KEY =
      "canonical/" + JOB.value() + "/attempt-2/canonical.jsonl";

  private final LoadManifest manifests = mock(LoadManifest.class);
  private final SourceObjectReader sources = mock(SourceObjectReader.class);
  private final CanonicalFileStore canonicalFiles = mock(CanonicalFileStore.class);
  private final CanonicalFile canonicalFile = mock(CanonicalFile.class);
  private final RejectedRecordStore rejectedRecords = mock(RejectedRecordStore.class);
  private final JobCompletion completion = mock(JobCompletion.class);
  private final PublishDailyMarketSummaries publication = mock(PublishDailyMarketSummaries.class);
  private final BhavcopyHandler handler = handler(CONTRACTS);

  @Test
  void handlesTheBhavcopyDataset() {
    assertThat(handler.dataset())
        .isEqualTo(new DatasetUrn("urn:bond-platform:dataset:bse-debt-trades"));
  }

  @Test
  void rejectsContractsThatDoNotReadCsv() {
    PinnedContracts json =
        new PinnedContracts(
            new SourceContract.Json(
                new ContractId("nsdl-security-json", "v1"),
                new DatasetUrn("urn:bond-platform:dataset:nsdl-security"),
                1,
                List.of(),
                List.of()),
            "sha256:s",
            CONTRACTS.mapping(),
            "sha256:m");

    assertThatThrownBy(() -> handler(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not read CSV");
  }

  @Test
  void storesTheCanonicalFileAndRejectedRowsThenPublishesAcceptedSummaries() {
    ClaimedJob job = claimed(pinnedVersions());
    givenSource(job, golden());
    when(canonicalFile.commit()).thenReturn(CANONICAL_KEY);

    handler.process(job);

    ArgumentCaptor<CanonicalRun> run = ArgumentCaptor.forClass(CanonicalRun.class);
    verify(canonicalFiles).create(run.capture());
    assertThat(run.getValue())
        .isEqualTo(
            new CanonicalRun(
                JOB,
                2,
                LocalDate.of(2026, 1, 1),
                FILE.bucket(),
                FILE.key(),
                "bse-debt-bhavcopy-csv-v1",
                "bse-debt-bhavcopy-mapping-v1"));
    verify(canonicalFile, times(8)).append(any(CanonicalRow.class));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<RejectedRow>> rejected = ArgumentCaptor.forClass(List.class);
    verify(rejectedRecords).saveAll(eq(run.getValue()), eq(RUN), rejected.capture());
    assertThat(rejected.getValue()).hasSize(5);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DailyMarketSummary>> accepted = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<JobOutcome> outcome = ArgumentCaptor.forClass(JobOutcome.class);
    InOrder order = inOrder(rejectedRecords, canonicalFile, completion, publication);
    order.verify(rejectedRecords).saveAll(any(), any(), anyList());
    order.verify(canonicalFile).commit();
    order.verify(completion).recordCanonicalFile(job, CANONICAL_KEY);
    order.verify(publication).publish(eq(job), accepted.capture(), outcome.capture());
    order.verify(canonicalFile).close();
    assertThat(accepted.getValue())
        .extracting(DailyMarketSummary::isin)
        .containsExactly(Isin.of("INE001A07AB1"), Isin.of("INE004D07GH4"), Isin.of("INE002B08CD2"));
    assertThat(outcome.getValue().status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(outcome.getValue().countsJson())
        .isEqualTo(
            "{\"sourceRecords\":8,\"acceptedRows\":3,\"invalidRows\":4,\"supersededRows\":1}");
  }

  @Test
  void fileWithOnlyAcceptedRowsStoresNoRejectedRows() {
    ClaimedJob job = claimed(pinnedVersions());
    givenSource(job, csv("973812,INE001A07AB1,100,101,99,100,1,1,100,100"));
    when(canonicalFile.commit()).thenReturn(CANONICAL_KEY);

    handler.process(job);

    verifyNoInteractions(rejectedRecords);
    verify(publication)
        .publish(
            eq(job),
            anyList(),
            eq(
                new JobOutcome(
                    JobStatus.COMPLETED,
                    "{\"sourceRecords\":1,\"acceptedRows\":1,\"invalidRows\":0,"
                        + "\"supersededRows\":0}",
                    0)));
  }

  @Test
  void rejectedFileFailsWithoutStoringOrPublishing() {
    ClaimedJob job = claimed(pinnedVersions());
    givenSource(job, "Security_cd,Open Price\n1,2\n".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> handler.process(job))
        .isInstanceOfSatisfying(
            PermanentFailureException.class,
            e -> assertThat(e.code()).isEqualTo("REQUIRED_HEADER_MISSING"));
    verify(canonicalFile, never()).append(any());
    verify(canonicalFile, never()).commit();
    verify(canonicalFile).close();
    verifyNoInteractions(rejectedRecords, completion, publication);
  }

  @Test
  void checksumMismatchFailsBeforeAnyCanonicalFileIsStarted() {
    ClaimedJob job = claimed(pinnedVersions());
    when(manifests.load(job)).thenReturn(manifest());
    when(sources.read(FILE)).thenReturn(new SourceRead.ChecksumMismatch(FILE, "size differs"));

    assertThatThrownBy(() -> handler.process(job))
        .isInstanceOfSatisfying(
            PermanentFailureException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CHECKSUM_MISMATCH");
              assertThat(e.getMessage()).isEqualTo("size differs");
            });
    verifyNoInteractions(canonicalFiles, publication);
  }

  @Test
  void jobPinnedToOtherContractsIsNotProcessed() {
    PinnedContracts deployed = CONTRACTS;
    ClaimedJob sourceDiffers =
        claimed(
            new PinnedContractVersions(
                new ContractId("bse-debt-bhavcopy-csv", "v0"),
                deployed.sourceHash(),
                deployed.mapping().id(),
                deployed.mappingHash()));
    ClaimedJob mappingDiffers =
        claimed(
            new PinnedContractVersions(
                deployed.source().id(),
                deployed.sourceHash(),
                new ContractId("bse-debt-bhavcopy-mapping", "v0"),
                deployed.mappingHash()));

    assertThatThrownBy(() -> handler.process(sourceDiffers))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "The job is pinned to bse-debt-bhavcopy-csv-v0,"
                + " but this deployment holds bse-debt-bhavcopy-csv-v1");
    assertThatThrownBy(() -> handler.process(mappingDiffers))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bse-debt-bhavcopy-mapping-v0");
    verifyNoInteractions(manifests);
  }

  @Test
  void manifestMissingAnExpectedInputIsDefect() {
    ClaimedJob job = claimed(pinnedVersions());
    when(manifests.load(job)).thenReturn(manifest(Map.of("exchangeName", "BSE")));
    when(sources.read(FILE)).thenReturn(verified(golden()));

    assertThatThrownBy(() -> handler.process(job))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The checked manifest has no input tradeDate");
  }

  private BhavcopyHandler handler(PinnedContracts contracts) {
    return new BhavcopyHandler(
        contracts,
        RuleRegistry.standard(),
        manifests,
        sources,
        canonicalFiles,
        rejectedRecords,
        completion,
        publication,
        TransactionOperations.withoutTransaction());
  }

  private void givenSource(ClaimedJob job, byte[] content) {
    when(manifests.load(job)).thenReturn(manifest());
    when(sources.read(FILE)).thenReturn(verified(content));
    when(canonicalFiles.create(any())).thenReturn(canonicalFile);
  }

  private static SourceRead verified(byte[] content) {
    return new SourceRead.Verified(new VerifiedSource(FILE, content, null, null));
  }

  private static Manifest manifest() {
    return manifest(Map.of("exchangeName", "BSE", "tradeDate", "2026-01-01"));
  }

  private static Manifest manifest(Map<String, String> inputs) {
    return new Manifest(
        "manifest-1",
        CONTRACTS.source().dataset(),
        "bse/2026-01-01",
        "daily-bhavcopy",
        "evt_1",
        "run_101",
        inputs,
        "sha256:" + "b".repeat(64),
        List.of(FILE));
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
            2,
            CONTRACTS.source().dataset(),
            "bse/2026-01-01",
            "daily-bhavcopy",
            "evt_1",
            "run_101",
            new OrderingGroup("2026-01-01"),
            "{\"exchangeName\":\"BSE\",\"tradeDate\":\"2026-01-01\"}",
            new ManifestLocation(
                "data-fetch-service-artifacts", "runs/run_101/manifest.json", null),
            "sha256:" + "b".repeat(64),
            versions);
    return new ClaimedJob(job, RUN, 2);
  }

  /** A bhavcopy with the ten selected headers and the given rows. */
  private static byte[] csv(String... rows) {
    return ("Security_cd,ISIN No.,Open Price,High Price,Low Price,Close Price,"
            + "Total Traded Volume,Number of Trades,Total Turnover,FACE VALUE\n"
            + String.join("\n", rows)
            + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] golden() {
    try (InputStream in =
        BhavcopyHandlerTest.class.getResourceAsStream("/fixtures/bse/BSE_fgroup01012026.csv")) {
      if (in == null) {
        throw new IllegalStateException("Missing golden bhavcopy");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
