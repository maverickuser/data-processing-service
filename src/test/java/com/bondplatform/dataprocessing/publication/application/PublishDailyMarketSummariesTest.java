package com.bondplatform.dataprocessing.publication.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.publication.domain.CouponSource;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetailsRequest;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.publication.domain.StoredCoupon;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PublishDailyMarketSummariesTest {

  private static final URI SOURCE = URI.create("urn:bond-platform:structured-file-processing:test");
  private static final Instant NOW = Instant.parse("2026-01-02T10:00:00Z");
  private static final JobOutcome OUTCOME =
      new JobOutcome(JobStatus.COMPLETED, "{\"sourceRecords\":3}", 0);

  private final SecurityRepository securities = mock(SecurityRepository.class);
  private final DailyMarketSummaryRepository summaries = mock(DailyMarketSummaryRepository.class);
  private final JobCompletion completion = mock(JobCompletion.class);
  private final OutboxEventStore outbox = mock(OutboxEventStore.class);
  private final ClaimedJob job = mock(ClaimedJob.class, RETURNS_DEEP_STUBS);
  private final PublishDailyMarketSummaries publish =
      new PublishDailyMarketSummaries(
          securities,
          summaries,
          completion,
          outbox,
          new SequentialIds(),
          Clock.fixed(NOW, ZoneOffset.UTC),
          SOURCE);

  @Test
  void createsSecuritiesRequestsTheirDetailsThenUpsertsSummariesThenFinishesJob() {
    List<DailyMarketSummary> accepted =
        List.of(summary("INE001A07AB1"), summary("INE002B08CD2"), summary("INE001A07AB1"));
    when(securities.insertMissing(any(), any())).thenReturn(Set.of(Isin.of("INE002B08CD2")));

    Set<Isin> created = publish.publish(job, accepted, List.of(), OUTCOME);

    assertThat(created).containsExactly(Isin.of("INE002B08CD2"));
    InOrder order = inOrder(securities, outbox, summaries, completion);
    order
        .verify(securities)
        .insertMissing(Set.of(Isin.of("INE001A07AB1"), Isin.of("INE002B08CD2")), NOW);
    SecurityDetailsRequest request =
        new SecurityDetailsRequest(SequentialIds.id(1), SOURCE, Isin.of("INE002B08CD2"), NOW);
    order
        .verify(outbox)
        .append(
            new NewOutboxEvent(
                SequentialIds.id(1),
                OutboxDestination.SECURITY_DETAILS,
                null,
                null,
                request.cloudEvent(),
                NOW));
    order.verify(summaries).upsertAll(accepted, NOW);
    order.verify(completion).complete(job, OUTCOME);
  }

  @Test
  void requestsDetailsOnlyForCreatedSecuritiesInIsinOrder() {
    when(securities.insertMissing(any(), any()))
        .thenReturn(Set.of(Isin.of("INE003C07EF3"), Isin.of("INE001A07AB1")));
    ArgumentCaptor<NewOutboxEvent> events = ArgumentCaptor.forClass(NewOutboxEvent.class);

    publish.publish(
        job,
        List.of(summary("INE003C07EF3"), summary("INE002B08CD2"), summary("INE001A07AB1")),
        List.of(),
        OUTCOME);

    verify(outbox, times(2)).append(events.capture());
    assertThat(events.getAllValues())
        .extracting(NewOutboxEvent::payloadJson)
        .map(json -> json.contains("isin/INE001A07AB1") ? "first" : "second")
        .containsExactly("first", "second");
  }

  @Test
  void timesAreKeptToMicroseconds() {
    PublishDailyMarketSummaries nanoClock =
        new PublishDailyMarketSummaries(
            securities,
            summaries,
            completion,
            outbox,
            new SequentialIds(),
            Clock.fixed(NOW.plusNanos(123_456_789), ZoneOffset.UTC),
            SOURCE);

    nanoClock.publish(job, List.of(), List.of(), OUTCOME);

    verify(summaries).upsertAll(List.of(), NOW.plusNanos(123_456_000));
  }

  @Test
  void fileWithoutAcceptedRowsOnlyFinishesJob() {
    JobOutcome failed = new JobOutcome(JobStatus.FAILED, "{\"sourceRecords\":1}", 1);

    Set<Isin> created = publish.publish(job, List.of(), List.of(), failed);

    assertThat(created).isEmpty();
    verify(outbox, never()).append(any());
    verify(securities).insertMissing(Set.of(), NOW);
    verify(summaries).upsertAll(List.of(), NOW);
    verify(completion).complete(job, failed);
  }

  @Test
  void failureStopsBeforeLaterSteps() {
    doThrow(new IllegalStateException("broken")).when(summaries).upsertAll(any(), any());

    assertThatThrownBy(
            () -> publish.publish(job, List.of(summary("INE001A07AB1")), List.of(), OUTCOME))
        .isInstanceOf(IllegalStateException.class);
    verify(completion, never()).complete(any(), any());
  }

  // U-COUPON-04
  @Test
  void storesCouponsByPrecedenceAfterLockingTheirSecuritiesAndBeforeTheSummaries() {
    ExchangeCoupon overNsdl = coupon("INE001A07AB1", "8.10");
    ExchangeCoupon keptNewer = coupon("INE002B08CD2", "9");
    ExchangeCoupon sameRateLater = coupon("INE003C07EF3", "10");
    ExchangeCoupon first = coupon("INE004D07GH4", "7.25");
    List<ExchangeCoupon> coupons = List.of(overNsdl, keptNewer, sameRateLater, first);
    when(securities.lockCoupons(any()))
        .thenReturn(
            Map.of(
                overNsdl.isin(),
                new StoredCoupon(Percent.parse("8"), CouponSource.NSDL, null),
                keptNewer.isin(),
                new StoredCoupon(
                    Percent.parse("8.5"), CouponSource.BSE_BHAVCOPY, LocalDate.of(2026, 1, 9)),
                sameRateLater.isin(),
                new StoredCoupon(
                    Percent.parse("10.00"),
                    CouponSource.BSE_BHAVCOPY,
                    LocalDate.of(2025, 12, 31))));

    publish.publish(job, List.of(), coupons, OUTCOME);

    InOrder order = inOrder(securities, summaries, completion);
    order.verify(securities).insertMissing(Set.of(), NOW);
    order
        .verify(securities)
        .lockCoupons(
            List.of(overNsdl.isin(), keptNewer.isin(), sameRateLater.isin(), first.isin()));
    order
        .verify(securities)
        .storeBhavcopyCoupons(List.of(overNsdl, first), List.of(sameRateLater), NOW);
    order.verify(summaries).upsertAll(List.of(), NOW);
    order.verify(completion).complete(job, OUTCOME);
  }

  /** Hands out 00000000-0000-0000-0000-000000000001, then ...002, and so on. */
  private static final class SequentialIds implements IdSupplier {
    private int next = 1;

    static UUID id(int n) {
      return new UUID(0, n);
    }

    @Override
    public UUID nextId() {
      return id(next++);
    }
  }

  private static ExchangeCoupon coupon(String isin, String rate) {
    return new ExchangeCoupon(
        Isin.of(isin),
        TradeDate.parseIso("2026-01-01"),
        Percent.parse(rate),
        new SourceReference(
            new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001")),
            "BSE_fgroup01012026.csv",
            "2"));
  }

  private static DailyMarketSummary summary(String isin) {
    return new DailyMarketSummary(
        Isin.of(isin),
        TradeDate.parseIso("2026-01-01"),
        ExchangeName.of("BSE"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new SourceReference(
            new JobId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001")),
            "BSE_fgroup01012026.csv",
            "2"));
  }
}
