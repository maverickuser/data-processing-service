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
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
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
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/** Publishing a JSON request's security details against PostgreSQL (I-JSON-02, 03, 05, 07). */
class PublishSecurityDetailsIT extends PostgresIntegrationTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final JsonFileCounts FILES = new JsonFileCounts(6, 6, 0, 0);

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private JobRunRepository jobRuns;
  @Autowired private TransactionOperations transactions;
  @Autowired private PublishSecurityDetails publish;

  @Test
  void firstRequestCreatesAndEnrichesTheSecurity() {
    ClaimedJob job = start("run_201");

    JobOutcome outcome =
        publish.publish(job, details(job, "Simple", listings(job)), FILES, completed(), 0);

    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 3, 1, 0));
    Map<String, Object> row = security();
    assertThat(row.get("issuer_name")).isEqualTo("ADITYA BIRLA HOUSING FINANCE LIMITED");
    assertThat(row.get("coupon_type")).isEqualTo("Simple");
    assertThat((BigDecimal) row.get("coupon_rate_value")).isEqualByComparingTo("8.94");
    assertThat(row.get("coupon_rate_unit")).isEqualTo("PERCENT");
    assertThat(source("couponType", "sourceRequestId")).isEqualTo(job.job().id().toString());
    assertThat(source("couponType", "sourceFile")).isEqualTo("INE831R08076_coupon-details.json");
    assertThat(source("couponType", "sourceLocation"))
        .isEqualTo("$.coupensVo.couponDetails.couponType");
    assertThat(count("security_listings")).isEqualTo(1);
    assertThat(jobStatus(job)).isEqualTo("COMPLETED");
  }

  /** I-JSON-02: the exit evidence of plan PR 35. */
  @Test
  void resubmittingIdenticalDataChangesNoRow() {
    ClaimedJob first = start("run_201");
    publish.publish(first, details(first, "Simple", listings(first)), FILES, completed(), 0);
    Map<String, Object> before = security();

    ClaimedJob second = start("run_202");
    JobOutcome outcome =
        publish.publish(second, details(second, "Simple", listings(second)), FILES, completed(), 0);

    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 0, 0, 0));
    assertThat(security()).isEqualTo(before);
    assertThat(count("security_listings")).isEqualTo(1);
    assertThat(jobStatus(second)).isEqualTo("COMPLETED");
  }

  /** I-JSON-03. */
  @Test
  void changedFieldUpdatesOnlyThatFieldAndItsSource() {
    ClaimedJob first = start("run_201");
    publish.publish(first, details(first, "Simple", List.of()), FILES, completed(), 0);
    OffsetDateTime firstUpdate = (OffsetDateTime) security().get("updated_at");

    ClaimedJob second = start("run_202");
    JobOutcome outcome =
        publish.publish(second, details(second, "Compound", List.of()), FILES, completed(), 0);

    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 1, 0, 0));
    assertThat(security().get("coupon_type")).isEqualTo("Compound");
    assertThat(source("couponType", "sourceRequestId")).isEqualTo(second.job().id().toString());
    assertThat(source("issuerName", "sourceRequestId")).isEqualTo(first.job().id().toString());
    assertThat((OffsetDateTime) security().get("updated_at")).isAfterOrEqualTo(firstUpdate);
  }

  /** I-JSON-07, at publication: clearing removes coverage and its source, assets stay stored. */
  @Test
  void unsecuredAfterSecuredClearsCoverageAndKeepsAssetRows() {
    ClaimedJob first = start("run_201");
    SourceReference cover = new SourceReference(first.job().id(), "a.json", "$.cover");
    Map<String, SecurityScalars.Field> secured = new LinkedHashMap<>();
    secured.put("collateralStatus", new SecurityScalars.Field(text("Secured"), cover));
    secured.put(
        "assetCoverage",
        new SecurityScalars.Field(new SecurityValue.Percentage(Percent.parse("100")), cover));
    publish.publish(
        first,
        new SecurityDetails(
            ISIN,
            new SecurityScalars(secured),
            new SecurityCollections(
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new SecurityEntry<>(
                        ISIN, new CollateralAsset("Book Debts", null, null), cover)))),
        FILES,
        completed(),
        0);

    ClaimedJob second = start("run_202");
    SourceReference unsecured = new SourceReference(second.job().id(), "a.json", "$.cover");
    JobOutcome outcome =
        publish.publish(
            second,
            new SecurityDetails(
                ISIN,
                new SecurityScalars(
                    Map.of(
                        "collateralStatus",
                        new SecurityScalars.Field(text("Unsecured"), unsecured)),
                    Set.of("assetCoverageBasis", "assetCoverage")),
                empty()),
            FILES,
            completed(),
            0);

    assertThat(outcome).isEqualTo(FILES.outcome(JobStatus.COMPLETED, 2, 0, 0));
    Map<String, Object> row = security();
    assertThat(row.get("collateral_status")).isEqualTo("Unsecured");
    assertThat(row.get("asset_coverage_value")).isNull();
    assertThat(row.get("asset_coverage_unit")).isNull();
    assertThat(source("assetCoverage", "sourceFile")).isNull();
    assertThat(count("security_collateral_assets")).isEqualTo(1);
  }

  /** I-JSON-05. */
  @Test
  void failureDuringPublicationRollsBackEveryChange() {
    ClaimedJob job = start("run_201");
    ClaimedJob notRunning = new ClaimedJob(job.job(), UUID.randomUUID(), job.attemptNumber());

    assertThatThrownBy(
            () ->
                publish.publish(
                    notRunning, details(job, "Simple", listings(job)), FILES, completed(), 0))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("securities")).isZero();
    assertThat(count("security_listings")).isZero();
    assertThat(jobStatus(job)).isEqualTo("PROCESSING");
  }

  @Test
  void failedRequestCreatesNoSecurity() {
    ClaimedJob job = start("run_201");

    publish.publish(
        job,
        new SecurityDetails(ISIN, new SecurityScalars(Map.of()), empty()),
        new JsonFileCounts(1, 0, 1, 0),
        JobStatus.FAILED,
        1);

    assertThat(count("securities")).isZero();
    assertThat(jobStatus(job)).isEqualTo("FAILED");
  }

  private ClaimedJob start(String fetchRunId) {
    Map<String, Object> event = SubmissionEvents.nsdl(fetchRunId, ISIN.value());
    JobId id = admitSubmission.admit(SubmissionEvents.submissionOf(event), event).jobId();
    UUID runId = UUID.randomUUID();
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              var job = jobRuns.lockForRun(id).orElseThrow();
              jobRuns.startRun(id, runId, 1, Instant.parse("2026-10-05T00:00:00Z"));
              return new ClaimedJob(job, runId, 1);
            }));
  }

  private static SecurityDetails details(
      ClaimedJob job, String couponType, List<SecurityEntry<Listing>> listings) {
    JobId id = job.job().id();
    Map<String, SecurityScalars.Field> fields = new LinkedHashMap<>();
    fields.put(
        "issuerName",
        new SecurityScalars.Field(
            text("ADITYA BIRLA HOUSING FINANCE LIMITED"),
            new SourceReference(id, "INE831R08076_isin-details.json", "$.issuerName")));
    fields.put(
        "couponRate",
        new SecurityScalars.Field(
            new SecurityValue.Percentage(Percent.parse("8.94")),
            new SourceReference(
                id, "INE831R08076_coupon-details.json", "$.coupensVo.couponDetails.couponRate")));
    fields.put(
        "couponType",
        new SecurityScalars.Field(
            text(couponType),
            new SourceReference(
                id, "INE831R08076_coupon-details.json", "$.coupensVo.couponDetails.couponType")));
    return new SecurityDetails(
        ISIN,
        new SecurityScalars(fields),
        new SecurityCollections(List.of(), listings, List.of(), List.of()));
  }

  private static List<SecurityEntry<Listing>> listings(ClaimedJob job) {
    return List.of(
        new SecurityEntry<>(
            ISIN,
            new Listing("NSE", LocalDate.of(2019, 6, 14)),
            new SourceReference(
                job.job().id(), "INE831R08076_listings.json", "$.listingDetails[0]")));
  }

  private static SecurityCollections empty() {
    return new SecurityCollections(List.of(), List.of(), List.of(), List.of());
  }

  private static SecurityValue text(String value) {
    return new SecurityValue.Text(value);
  }

  private static JobStatus completed() {
    return JobStatus.COMPLETED;
  }

  private Map<String, Object> security() {
    return jdbc.sql(
            """
            SELECT issuer_name, coupon_type, coupon_rate_value, coupon_rate_unit,
                   collateral_status, asset_coverage_value, asset_coverage_unit,
                   field_sources::text AS field_sources, updated_at
            FROM securities_data.securities WHERE isin = :isin
            """)
        .param("isin", ISIN.value())
        .query()
        .singleRow();
  }

  private @Nullable String source(String field, String part) {
    return jdbc.sql(
            "SELECT field_sources -> :field ->> :part FROM securities_data.securities"
                + " WHERE isin = :isin")
        .param("field", field)
        .param("part", part)
        .param("isin", ISIN.value())
        .query(String.class)
        .optional()
        .orElse(null);
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
