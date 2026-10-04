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
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/** Publishing a CSV file's summaries against PostgreSQL (I-CSV-04, I-CSV-05). */
class PublishDailyMarketSummariesIT extends PostgresIntegrationTest {

  private static final String ALPHA = "INE001A07AB1";
  private static final String BETA = "INE002B08CD2";

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private TransactionOperations transactions;
  @Autowired private PublishDailyMarketSummaries publish;

  @Test
  void publishesSummariesCreatesSecuritiesAndFinishesJob() {
    ClaimedJob job = start("run_101");

    var created =
        publish.publish(
            job,
            List.of(summary(job, ALPHA, "100.5"), summary(job, BETA, "99")),
            outcome(JobStatus.COMPLETED));

    assertThat(created).containsExactlyInAnyOrder(Isin.of(ALPHA), Isin.of(BETA));
    assertThat(stored(ALPHA)).containsEntry("source_request_id", job.job().id().value());
    assertThat(jobStatus(job)).isEqualTo("COMPLETED");
  }

  /** I-CSV-04. */
  @Test
  void resubmissionReplacesItsKeysWithNullsAndLeavesOthersUnchanged() {
    ClaimedJob first = start("run_101");
    publish.publish(
        first,
        List.of(summary(first, ALPHA, "100.5"), summary(first, BETA, "99")),
        outcome(JobStatus.COMPLETED));

    ClaimedJob second = start("run_102");
    var created =
        publish.publish(
            second, List.of(summary(second, ALPHA, null)), outcome(JobStatus.COMPLETED));

    assertThat(created).isEmpty();
    assertThat(stored(ALPHA))
        .containsEntry("close_price", null)
        .containsEntry("source_request_id", second.job().id().value());
    assertThat((BigDecimal) stored(BETA).get("close_price")).isEqualByComparingTo("99");
    assertThat(stored(BETA)).containsEntry("source_request_id", first.job().id().value());
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
                    outcome(JobStatus.COMPLETED)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("securities")).isZero();
    assertThat(count("security_daily_market_summaries")).isZero();
    assertThat(jobStatus(job)).isEqualTo("PROCESSING");
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
