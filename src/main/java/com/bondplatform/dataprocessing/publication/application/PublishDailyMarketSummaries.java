package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.publication.domain.CouponPrecedence;
import com.bondplatform.dataprocessing.publication.domain.CouponPrecedence.BhavcopyCouponChange;
import com.bondplatform.dataprocessing.publication.domain.CouponSource;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.SecurityDetailsRequest;
import com.bondplatform.dataprocessing.publication.domain.StoredCoupon;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a completely evaluated CSV file: its summaries and the job's outcome commit together,
 * or nothing does (LLD sections 7 and 8.1).
 *
 * <p>In one transaction it creates an ISIN-only security for each ISIN that has none, records one
 * security-details request in the outbox for each security it created (LLD section 14.3), stores
 * the coupon rates the file reports where they take precedence (decision 0004), upserts every
 * summary, and finishes the job. A failure at any step rolls back every summary, security, coupon,
 * and request of the file.
 *
 * <p>A file's rate that differs from a stored NSDL rate is logged as a warning; the file's rate is
 * stored all the same.
 */
public class PublishDailyMarketSummaries {

  private static final Logger LOG = LoggerFactory.getLogger(PublishDailyMarketSummaries.class);

  private final SecurityRepository securities;
  private final DailyMarketSummaryRepository summaries;
  private final JobCompletion completion;
  private final OutboxEventStore outbox;
  private final IdSupplier ids;
  private final Clock clock;
  private final URI eventSource;

  /**
   * Creates the use case.
   *
   * @param eventSource the producer identity every security-details request carries
   */
  public PublishDailyMarketSummaries(
      SecurityRepository securities,
      DailyMarketSummaryRepository summaries,
      JobCompletion completion,
      OutboxEventStore outbox,
      IdSupplier ids,
      Clock clock,
      URI eventSource) {
    this.securities = securities;
    this.summaries = summaries;
    this.completion = completion;
    this.outbox = outbox;
    this.ids = ids;
    this.clock = clock;
    this.eventSource = eventSource;
  }

  /**
   * Stores the file's accepted summaries and finishes the job with {@code outcome}.
   *
   * @param job the attempt being finished
   * @param accepted the summaries of every accepted row, in file order
   * @param coupons the coupon rates of the accepted rows that report one
   * @param outcome the job's final status, counts, and error count
   * @return the ISINs whose securities this call created; their details are already requested
   * @throws IllegalStateException if the job is not running this attempt; nothing is stored
   */
  @Transactional
  public Set<Isin> publish(
      ClaimedJob job,
      List<DailyMarketSummary> accepted,
      List<ExchangeCoupon> coupons,
      JobOutcome outcome) {
    // PostgreSQL keeps microseconds, so the event's time equals the stored creation time.
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Set<Isin> isins = new LinkedHashSet<>();
    accepted.forEach(summary -> isins.add(summary.isin()));
    Set<Isin> created = securities.insertMissing(isins, now);
    created.stream()
        .sorted(Comparator.comparing(Isin::value))
        .forEach(isin -> outbox.append(request(isin, now)));
    storeCoupons(job, coupons, now);
    summaries.upsertAll(accepted, now);
    completion.complete(job, outcome);
    return created;
  }

  private void storeCoupons(ClaimedJob job, List<ExchangeCoupon> coupons, Instant now) {
    Map<Isin, StoredCoupon> stored =
        securities.lockCoupons(coupons.stream().map(ExchangeCoupon::isin).toList());
    List<ExchangeCoupon> replaced = new ArrayList<>();
    List<ExchangeCoupon> advanced = new ArrayList<>();
    for (ExchangeCoupon coupon : coupons) {
      StoredCoupon current = stored.getOrDefault(coupon.isin(), StoredCoupon.NONE);
      if (CouponPrecedence.disagrees(current, CouponSource.BSE_BHAVCOPY, coupon.rate())) {
        LOG.warn(
            "Coupon rates differ, jobId={}, isin={}, bhavcopy={}, stored {}={}",
            job.job().id().value(),
            coupon.isin().value(),
            coupon.rate().value().toPlainString(),
            current.source(),
            current.rate() == null ? null : current.rate().value().toPlainString());
      }
      BhavcopyCouponChange change = CouponPrecedence.forBhavcopy(current, coupon);
      if (change == BhavcopyCouponChange.REPLACE) {
        replaced.add(coupon);
      } else if (change == BhavcopyCouponChange.ADVANCE_TRADE_DATE) {
        advanced.add(coupon);
      }
    }
    securities.storeBhavcopyCoupons(replaced, advanced, now);
  }

  private NewOutboxEvent request(Isin isin, Instant now) {
    SecurityDetailsRequest request =
        new SecurityDetailsRequest(ids.nextId(), eventSource, isin, now);
    return new NewOutboxEvent(
        request.id(), OutboxDestination.SECURITY_DETAILS, null, null, request.cloudEvent(), now);
  }
}
