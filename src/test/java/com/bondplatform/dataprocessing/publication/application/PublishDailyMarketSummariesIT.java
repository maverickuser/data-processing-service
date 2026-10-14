package com.bondplatform.dataprocessing.publication.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.admission.application.AdmitSubmission;
import com.bondplatform.dataprocessing.job.application.JobRunRepository;
import com.bondplatform.dataprocessing.job.domain.ClaimedJob;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/** Publishing a CSV file's summaries against PostgreSQL (I-CSV-04, I-CSV-05, I-EVT-01..04). */
class PublishDailyMarketSummariesIT extends PostgresIntegrationTest {

  private static final String ALPHA = "INE001A07AB1";
  private static final String BETA = "INE002B08CD2";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private TransactionOperations transactions;
  @Autowired private PublishDailyMarketSummaries publish;

  /** I-EVT-01 (outbox part). */
  @Test
  void publishesSummariesCreatesSecuritiesRequestsTheirDetailsAndFinishesJob() {
    ClaimedJob job = start("run_101");

    var created =
        publish.publish(
            job,
            List.of(summary(job, ALPHA, "100.5"), summary(job, BETA, "99")),
            List.of(),
            outcome(JobStatus.COMPLETED));

    assertThat(created).containsExactlyInAnyOrder(Isin.of(ALPHA), Isin.of(BETA));
    assertThat(stored(ALPHA)).containsEntry("source_request_id", job.job().id().value());
    assertThat(jobStatus(job)).isEqualTo("COMPLETED");
    assertThat(detailRequests()).containsExactly("isin/" + ALPHA, "isin/" + BETA);
  }

  /** I-CSV-04, and I-EVT-02: an existing ISIN-only security gets no new request. */
  @Test
  void resubmissionReplacesItsKeysWithNullsAndLeavesOthersUnchanged() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first,
        List.of(summary(first, ALPHA, "100.5"), summary(first, BETA, "99")),
        List.of(),
        outcome(JobStatus.COMPLETED));

    ClaimedJob second = start("run_102");
    var created =
        publish.publish(
            second, List.of(summary(second, ALPHA, null)), List.of(), outcome(JobStatus.COMPLETED));

    assertThat(created).isEmpty();
    assertThat(stored(ALPHA))
        .containsEntry("close_price", null)
        .containsEntry("source_request_id", second.job().id().value());
    assertThat((BigDecimal) stored(BETA).get("close_price")).isEqualByComparingTo("99");
    assertThat(stored(BETA)).containsEntry("source_request_id", first.job().id().value());
    assertThat(detailRequests()).containsExactly("isin/" + ALPHA, "isin/" + BETA);
  }

  /** I-EVT-03. */
  @Test
  void twoJobsIntroducingOneIsinTogetherCreateOneSecurityAndOneRequest() throws Exception {
    ClaimedJob first = start("run_101");
    ClaimedJob second = start("run_102");

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<Set<Isin>>> results =
          pool.invokeAll(
              List.of(
                  () ->
                      publish.publish(
                          first, List.of(summary(first, ALPHA, "1")), List.of(), completed()),
                  () ->
                      publish.publish(
                          second, List.of(summary(second, ALPHA, "2")), List.of(), completed())));
      int created = 0;
      for (Future<Set<Isin>> result : results) {
        created += result.get().size();
      }
      assertThat(created).isEqualTo(1);
    }

    assertThat(count("securities")).isEqualTo(1);
    assertThat(detailRequests()).containsExactly("isin/" + ALPHA);
  }

  /** I-CSV-05. */
  @Test
  void failureDuringPublicationRollsBackEverySummaryAndSecurity() {
    ClaimedJob job = start("run_101");
    ClaimedJob notRunning = new ClaimedJob(job.job(), UUID.randomUUID(), job.attemptNumber());

    assertThatThrownBy(
            () ->
                publish.publish(
                    notRunning,
                    List.of(summary(job, ALPHA, "100.5"), summary(job, BETA, "99")),
                    List.of(),
                    outcome(JobStatus.COMPLETED)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("securities")).isZero();
    assertThat(count("security_daily_market_summaries")).isZero();
    assertThat(jobStatus(job)).isEqualTo("PROCESSING");
    assertThat(detailRequests()).isEmpty();
  }

  /** I-COUPON-01: the bhavcopy rate replaces NSDL's, and the latest trade date wins. */
  @Test
  void bhavcopyCouponReplacesNsdlAndAnOlderTradeDateNeverReplacesANewerOne() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first, List.of(summary(first, BETA, "99")), List.of(), outcome(JobStatus.COMPLETED));
    jdbc.sql(
            """
            UPDATE securities_data.securities
            SET coupon_rate_value = 9, coupon_rate_unit = 'PERCENT', coupon_rate_source = 'NSDL'
            WHERE isin = :isin
            """)
        .param("isin", BETA)
        .update();

    ClaimedJob day5 = start("run_102");
    publish.publish(
        day5,
        List.of(summary(day5, ALPHA, "100"), summary(day5, BETA, "99")),
        List.of(
            coupon(day5, ALPHA, "8.10", "2026-01-05"), coupon(day5, BETA, "9.25", "2026-01-05")),
        outcome(JobStatus.COMPLETED));

    assertThat(coupon(ALPHA))
        .containsEntry("coupon_rate_source", "BSE_BHAVCOPY")
        .containsEntry("coupon_rate_unit", "PERCENT")
        .containsEntry("trade_date", "2026-01-05")
        .containsEntry("source_request_id", day5.job().id().toString());
    assertThat((BigDecimal) coupon(BETA).get("coupon_rate_value")).isEqualByComparingTo("9.25");
    assertThat(coupon(BETA)).containsEntry("coupon_rate_source", "BSE_BHAVCOPY");
    final Object updatedAfterDay5 = coupon(ALPHA).get("updated_at");

    ClaimedJob day2 = start("run_103");
    publish.publish(
        day2,
        List.of(summary(day2, ALPHA, "100")),
        List.of(coupon(day2, ALPHA, "7.50", "2026-01-02")),
        outcome(JobStatus.COMPLETED));

    assertThat((BigDecimal) coupon(ALPHA).get("coupon_rate_value")).isEqualByComparingTo("8.10");
    assertThat(coupon(ALPHA)).containsEntry("trade_date", "2026-01-05");

    ClaimedJob day9 = start("run_104");
    publish.publish(
        day9,
        List.of(summary(day9, ALPHA, "100")),
        List.of(coupon(day9, ALPHA, "8.1", "2026-01-09")),
        outcome(JobStatus.COMPLETED));

    assertThat(coupon(ALPHA))
        .containsEntry("trade_date", "2026-01-09")
        .containsEntry("source_request_id", day5.job().id().toString())
        .containsEntry("updated_at", updatedAfterDay5);
  }

  /** I-DETAILS-01: a security still without coupon is asked for again, a day apart, three times. */
  @Test
  void securityWithoutCouponIsRequestedAgainADayApartAtMostThreeTimesInAll() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first, List.of(summary(first, ALPHA, "100")), List.of(), outcome(JobStatus.COMPLETED));
    assertThat(detailRequests()).containsExactly("isin/" + ALPHA);
    assertThat(requestCount(ALPHA)).isEqualTo(1);

    ClaimedJob sameDay = start("run_102");
    publish.publish(
        sameDay, List.of(summary(sameDay, ALPHA, "100")), List.of(), outcome(JobStatus.COMPLETED));
    assertThat(detailRequests()).hasSize(1);

    for (String run : List.of("run_103", "run_104", "run_105")) {
      jdbc.sql(
              "UPDATE securities_data.securities"
                  + " SET details_requested_at = details_requested_at - INTERVAL '2 days'")
          .update();
      ClaimedJob later = start(run);
      publish.publish(
          later, List.of(summary(later, ALPHA, "100")), List.of(), outcome(JobStatus.COMPLETED));
    }

    assertThat(detailRequests()).hasSize(3);
    assertThat(requestCount(ALPHA)).isEqualTo(3);
  }

  /** I-DETAILS-01: a security with every counted detail is not requested again. */
  @Test
  void securityWithEveryCountedDetailIsNotRequestedAgain() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first,
        List.of(summary(first, ALPHA, "100")),
        List.of(coupon(first, ALPHA, "8.10", "2026-01-01")),
        outcome(JobStatus.COMPLETED));
    completeDetails(ALPHA);
    jdbc.sql(
            "UPDATE securities_data.securities"
                + " SET details_requested_at = details_requested_at - INTERVAL '2 days'")
        .update();

    ClaimedJob later = start("run_102");
    publish.publish(
        later, List.of(summary(later, ALPHA, "100")), List.of(), outcome(JobStatus.COMPLETED));

    assertThat(detailRequests()).containsExactly("isin/" + ALPHA);
    assertThat(requestCount(ALPHA)).isEqualTo(1);
  }

  /** I-DETAILS-02: a coupon alone is not enough; missing cash flows still ask again. */
  @Test
  void securityWithCouponButMissingOtherDetailsIsRequestedAgain() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first,
        List.of(summary(first, ALPHA, "100")),
        List.of(coupon(first, ALPHA, "8.10", "2026-01-01")),
        outcome(JobStatus.COMPLETED));
    jdbc.sql(
            "UPDATE securities_data.securities"
                + " SET details_requested_at = details_requested_at - INTERVAL '2 days'")
        .update();

    ClaimedJob later = start("run_102");
    publish.publish(
        later, List.of(summary(later, ALPHA, "100")), List.of(), outcome(JobStatus.COMPLETED));

    assertThat(detailRequests()).containsExactly("isin/" + ALPHA, "isin/" + ALPHA);
    assertThat(requestCount(ALPHA)).isEqualTo(2);
  }

  /** Stores a cash flow, a current rating and a collateral status for the security. */
  private void completeDetails(String isin) {
    jdbc.sql(
            "UPDATE securities_data.securities SET collateral_status = 'Secured'"
                + " WHERE isin = :isin")
        .param("isin", isin)
        .update();
    for (String insert :
        List.of(
            "INSERT INTO securities_data.security_cash_flows (id, isin, event_type, due_date,"
                + " source_request_id, source_file, source_location, first_recorded_at)"
                + " VALUES (:id, :isin, 'Interest', DATE '2027-06-08', :request, 'a.json', '$.x',"
                + " now())",
            "INSERT INTO securities_data.security_ratings (id, isin, source_category, rating,"
                + " source_request_id, source_file, source_location, first_recorded_at)"
                + " VALUES (:id, :isin, 'CURRENT', 'AAA', :request, 'a.json', '$.x', now())")) {
      jdbc.sql(insert)
          .param("id", UUID.randomUUID())
          .param("isin", isin)
          .param("request", UUID.randomUUID())
          .update();
    }
  }

  private int requestCount(String isin) {
    return jdbc.sql(
            "SELECT details_request_count FROM securities_data.securities WHERE isin = :isin")
        .param("isin", isin)
        .query(Integer.class)
        .single();
  }

  private ClaimedJob start(String fetchRunId) {
    Map<String, Object> event = SubmissionEvents.bse(fetchRunId, "2026-01-01");
    JobId id = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    UUID runId = UUID.randomUUID();
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              var job = jobRuns.lockForRun(id).orElseThrow();
              jobRuns.startRun(id, runId, 1, Instant.parse("2026-01-02T00:00:00Z"));
              return new ClaimedJob(job, runId, 1);
            }));
  }

  private static DailyMarketSummary summary(
      ClaimedJob job, String isin, @Nullable String closePrice) {
    return new DailyMarketSummary(
        Isin.of(isin),
        TradeDate.parseIso("2026-01-01"),
        ExchangeName.of("BSE"),
        "973812",
        null,
        null,
        null,
        closePrice == null ? null : new BigDecimal(closePrice),
        BigInteger.TEN,
        BigInteger.ONE,
        null,
        new BigDecimal("1000"),
        new SourceReference(job.job().id(), "BSE_fgroup01012026.csv", "2"));
  }

  private static ExchangeCoupon coupon(ClaimedJob job, String isin, String rate, String tradeDate) {
    return new ExchangeCoupon(
        Isin.of(isin),
        TradeDate.parseIso(tradeDate),
        Percent.parse(rate),
        new SourceReference(job.job().id(), "BSE_fgroup01012026.csv", "2"));
  }

  private Map<String, Object> coupon(String isin) {
    return jdbc.sql(
            """
            SELECT coupon_rate_value, coupon_rate_unit, coupon_rate_source,
                   coupon_rate_trade_date::text AS trade_date,
                   field_sources -> 'couponRate' ->> 'sourceRequestId' AS source_request_id,
                   updated_at
            FROM securities_data.securities WHERE isin = :isin
            """)
        .param("isin", isin)
        .query()
        .singleRow();
  }

  private static JobOutcome completed() {
    return outcome(JobStatus.COMPLETED);
  }

  private List<String> detailRequests() {
    return jdbc.sql(
            """
            SELECT payload ->> 'subject' FROM data_processing.outbox_events
            WHERE destination = 'SECURITY_DETAILS' ORDER BY payload ->> 'subject'
            """)
        .query(String.class)
        .list();
  }

  private static JobOutcome outcome(JobStatus status) {
    return new JobOutcome(
        status,
        "{\"sourceRecords\":2,\"acceptedRows\":2,\"invalidRows\":0,\"supersededRows\":0}",
        0);
  }

  private Map<String, Object> stored(String isin) {
    return jdbc.sql(
            """
            SELECT close_price, source_request_id
            FROM securities_data.security_daily_market_summaries
            WHERE isin = :isin AND trade_date = DATE '2026-01-01' AND exchange_name = 'BSE'
            """)
        .param("isin", isin)
        .query()
        .singleRow();
  }

  private String jobStatus(ClaimedJob job) {
    return jdbc.sql("SELECT status FROM data_processing.ingestion_requests WHERE id = :id")
        .param("id", job.job().id().value())
        .query(String.class)
        .single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM securities_data." + table).query(Long.class).single();
  }
}
