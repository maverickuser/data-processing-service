package com.bondplatform.dataprocessing.publication.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.JsonFileCounts;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetails;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PublishSecurityDetailsTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00.123456789Z");
  private static final Instant STORED_NOW = Instant.parse("2026-10-05T10:00:00.123456Z");
  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final SourceReference SOURCE =
      new SourceReference(
          JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10"),
          "INE831R08076_isin-details.json",
          "$.issuerName");
  private static final JsonFileCounts FILES = new JsonFileCounts(6, 6, 0, 0);

  private final SecurityRepository securities = mock(SecurityRepository.class);
  private final SecurityCollectionRepository collections = mock(SecurityCollectionRepository.class);
  private final JobCompletion completion = mock(JobCompletion.class);
  private final ClaimedJob job = mock(ClaimedJob.class);
  private final PublishSecurityDetails publish =
      new PublishSecurityDetails(
          securities, collections, completion, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void storesOnlyChangedFieldsAppendsEntriesThenFinishesJob() {
    SecurityDetails details =
        details(
            new SecurityScalars(
                Map.of(
                    "issuerName", field("Acme Finance"),
                    "couponType", field("Simple")),
                Set.of("assetCoverage")),
            new SecurityCollections(
                List.of(
                    new SecurityEntry<>(
                        ISIN, new CashFlow("Interest", null, null, null, null, null), SOURCE)),
                List.of(new SecurityEntry<>(ISIN, new Listing("NSE", null), SOURCE)),
                List.of(),
                List.of()));
    when(securities.lockValues(ISIN))
        .thenReturn(Map.of("couponType", new SecurityValue.Text("Simple")));
    when(collections.appendCashFlows(any(), any())).thenReturn(1);
    when(collections.appendListings(any(), any())).thenReturn(0);

    JobOutcome outcome = publish.publish(job, details, FILES, JobStatus.COMPLETED, 0);

    InOrder order = inOrder(securities, collections, completion);
    order.verify(securities).insertMissing(List.of(ISIN), STORED_NOW);
    order.verify(securities).lockValues(ISIN);
    order
        .verify(securities)
        .applyChanges(
            ISIN, new SecurityScalars(Map.of("issuerName", field("Acme Finance"))), STORED_NOW);
    order.verify(collections).appendCashFlows(details.collections().cashFlows(), STORED_NOW);
    order.verify(completion).complete(job, outcome);
    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 1, 1, 0));
  }

  // The status is decided by the caller: unchanged valid data still completes, with zero changes
  @Test
  void identicalDataChangesNothingAndStillCompletes() {
    SecurityDetails details =
        details(
            new SecurityScalars(Map.of("couponType", field("Simple"))),
            new SecurityCollections(List.of(), List.of(), List.of(), List.of()));
    when(securities.lockValues(ISIN))
        .thenReturn(Map.of("couponType", new SecurityValue.Text("Simple")));

    JobOutcome outcome = publish.publish(job, details, FILES, JobStatus.COMPLETED, 0);

    verify(securities).applyChanges(ISIN, new SecurityScalars(Map.of()), STORED_NOW);
    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 0, 0, 0));
    verify(completion).complete(job, outcome);
  }

  @Test
  void failedRequestPublishesNothingAndFinishesJob() {
    SecurityDetails details =
        details(
            new SecurityScalars(Map.of()),
            new SecurityCollections(List.of(), List.of(), List.of(), List.of()));
    JsonFileCounts files = new JsonFileCounts(2, 1, 1, 3);

    JobOutcome outcome = publish.publish(job, details, files, JobStatus.FAILED, 4);

    verifyNoInteractions(securities, collections);
    assertThat(outcome).isEqualTo(files.outcome(JobStatus.FAILED, 0, 0, 4));
    verify(completion).complete(job, outcome);
  }

  private static SecurityDetails details(SecurityScalars scalars, SecurityCollections collections) {
    return new SecurityDetails(ISIN, scalars, collections);
  }

  private static SecurityScalars.Field field(String text) {
    return new SecurityScalars.Field(new SecurityValue.Text(text), SOURCE);
  }
}
