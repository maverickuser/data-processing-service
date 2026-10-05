package com.bondplatform.dataprocessing.canonical.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bondplatform.dataprocessing.canonical.adapter.json.CanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.adapter.json.JsonCanonicalLineWriter;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.JsonEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Checks what the store sends to JDBC. Whether the SQL does what it should is proven against
 * PostgreSQL in {@code RejectedRecordStoreIT}.
 */
class JdbcRejectedRecordStoreTest {

  private static final List<CanonicalRow> ROWS = GoldenBhavcopy.rows();
  private static final UUID RUN_ID = new UUID(0, 99);
  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

  private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
  private final AtomicLong nextId = new AtomicLong(1);
  private final JdbcRejectedRecordStore store =
      new JdbcRejectedRecordStore(
          jdbc, () -> new UUID(0, nextId.getAndIncrement()), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void bindsEveryRecordColumnInOneBatch() {
    RejectedRow superseded = RejectedRow.of(ROWS.get(1), "isin", 1);
    RejectedRow blankIsin = RejectedRow.of(ROWS.get(5), "isin", 2);

    store.saveAll(GoldenBhavcopy.RUN, RUN_ID, List.of(superseded, blankIsin));

    SqlParameterSource[] records = batch(JdbcRejectedRecordStore.INSERT_RECORD);
    assertThat(records).hasSize(2);
    SqlParameterSource first = records[0];
    assertThat(first.getValue("id")).isEqualTo(new UUID(0, 1));
    assertThat(first.getValue("processingRunId")).isEqualTo(RUN_ID);
    assertThat(first.getValue("jobId")).isEqualTo(GoldenBhavcopy.RUN.jobId().value());
    assertThat(first.getValue("bucket")).isEqualTo("data-fetch-service-artifacts");
    assertThat(first.getValue("key")).isEqualTo(GoldenBhavcopy.RUN.sourceKey());
    assertThat(first.getValue("isin")).isEqualTo("INE002B08CD2");
    assertThat(first.getValue("recordNumber")).isEqualTo(3);
    assertThat(first.getValue("disposition")).isEqualTo("QUARANTINED_SUPERSEDED");
    assertThat(first.getValue("record"))
        .isEqualTo(CanonicalLineWriter.line(GoldenBhavcopy.RUN, ROWS.get(1)));
    assertThat(first.getValue("createdAt"))
        .isEqualTo(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
    assertThat(records[1].getValue("isin")).isNull();
    assertThat(records[1].getValue("disposition")).isEqualTo("QUARANTINED_INVALID");
  }

  @Test
  void rowLevelIssueHasNoFieldOrRawValue() {
    store.saveAll(GoldenBhavcopy.RUN, RUN_ID, List.of(RejectedRow.of(ROWS.get(1), "isin", 4)));

    SqlParameterSource[] issues = batch(JdbcRejectedRecordStore.INSERT_ISSUE);
    assertThat(issues).hasSize(1);
    SqlParameterSource issue = issues[0];
    assertThat(issue.getValue("id")).isEqualTo(new UUID(0, 2));
    assertThat(issue.getValue("processingRunId")).isEqualTo(RUN_ID);
    assertThat(issue.getValue("rejectedRecordId")).isEqualTo(new UUID(0, 1));
    assertThat(issue.getValue("sequenceNumber")).isEqualTo(4L);
    assertThat(issue.getValue("code")).isEqualTo("DUPLICATE_ISIN_SUPERSEDED");
    assertThat(issue.getValue("isin")).isEqualTo("INE002B08CD2");
    assertThat(issue.getValue("sourceFileName")).isEqualTo("BSE_fgroup01012026.csv");
    assertThat(issue.getValue("recordNumber")).isEqualTo(3);
    assertThat(issue.getValue("field")).isNull();
    assertThat(issue.getValue("rawValue")).isNull();
    assertThat((String) issue.getValue("message")).contains("Record 6");
  }

  @Test
  void fieldIssueNamesItsFieldAndKeepsRawValueAsJsonString() {
    store.saveAll(GoldenBhavcopy.RUN, RUN_ID, List.of(RejectedRow.of(ROWS.get(7), "isin", 1)));

    SqlParameterSource issue = batch(JdbcRejectedRecordStore.INSERT_ISSUE)[0];
    assertThat(issue.getValue("code")).isEqualTo("NEGATIVE_VALUE");
    assertThat(issue.getValue("field")).isEqualTo("turnover");
    assertThat(issue.getValue("rawValue")).isEqualTo("\"-1000\"");
  }

  @Test
  void emptyBatchSendsNothing() {
    store.saveAll(GoldenBhavcopy.RUN, RUN_ID, List.of());

    verifyNoInteractions(jdbc);
  }

  @Test
  void jsonRejectionsAreStoredByPathWithIssuesNumberedAcrossFiles() {
    JsonFileEvidence withErrors =
        JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED));

    long errorCount =
        store.saveJson(JsonEvidence.RUN, RUN_ID, List.of(JsonEvidence.skipped(), withErrors));

    assertThat(errorCount).isEqualTo(5);
    SqlParameterSource[] records = batch(JdbcRejectedRecordStore.INSERT_JSON_RECORD);
    assertThat(records).hasSize(5);
    assertThat(records[0].getValue("jsonPath")).isEqualTo("$");
    assertThat(records[0].getValue("disposition")).isEqualTo("FILE_SKIPPED");
    assertThat(records[0].getValue("key")).isEqualTo(JsonEvidence.skipped().key());
    SqlParameterSource section = records[1];
    assertThat(section.getValue("id")).isEqualTo(new UUID(0, 3));
    assertThat(section.getValue("processingRunId")).isEqualTo(RUN_ID);
    assertThat(section.getValue("jobId")).isEqualTo(JsonEvidence.RUN.jobId().value());
    assertThat(section.getValue("bucket")).isEqualTo(withErrors.bucket());
    assertThat(section.getValue("key")).isEqualTo(withErrors.key());
    assertThat(section.getValue("isin")).isEqualTo("INE831R08076");
    assertThat(section.getValue("jsonPath")).isEqualTo("$.currentRatings");
    assertThat(section.getValue("disposition")).isEqualTo("SECTION_REJECTED");
    assertThat(section.getValue("record"))
        .isEqualTo(JsonCanonicalLineWriter.record(withErrors.rejections().getFirst()));
    assertThat(section.getValue("createdAt"))
        .isEqualTo(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
    assertThat(batch(JdbcRejectedRecordStore.INSERT_JSON_ISSUE))
        .extracting(issue -> issue.getValue("sequenceNumber"))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void jsonFieldIssueKeepsFieldPathTypedRawValueAndFileName() {
    store.saveJson(
        JsonEvidence.RUN,
        RUN_ID,
        List.of(JsonEvidence.read(JsonEvidence.WITH_ERRORS, List.of(JsonEvidence.IGNORED))));

    SqlParameterSource[] issues = batch(JdbcRejectedRecordStore.INSERT_JSON_ISSUE);
    SqlParameterSource rate = issues[1];
    assertThat(rate.getValue("id")).isEqualTo(new UUID(0, 4));
    assertThat(rate.getValue("processingRunId")).isEqualTo(RUN_ID);
    assertThat(rate.getValue("rejectedRecordId")).isEqualTo(new UUID(0, 3));
    assertThat(rate.getValue("isin")).isEqualTo("INE831R08076");
    assertThat(rate.getValue("sourceFileName")).isEqualTo("INE831R08076_instrument-details.json");
    assertThat(rate.getValue("field")).isEqualTo("coupon_rate");
    assertThat(rate.getValue("jsonPath")).isEqualTo("$.coupensVo.couponDetails.couponRate");
    assertThat(rate.getValue("rawValue")).isEqualTo("\"abc\"");
    assertThat(rate.getValue("actionTaken")).isNull();
    SqlParameterSource section = issues[0];
    assertThat(section.getValue("code")).isEqualTo("INVALID_TYPE");
    assertThat(section.getValue("field")).isNull();
    assertThat(section.getValue("rawValue")).isNull();
    SqlParameterSource ignored = issues[3];
    assertThat(ignored.getValue("code")).isEqualTo("CONFLICTING_COLLATERAL_DATA");
    assertThat(ignored.getValue("rawValue")).isEqualTo("100");
    assertThat(ignored.getValue("actionTaken")).isEqualTo("Ignored supplied coverage.");
    assertThat(ignored.getValue("message")).isEqualTo("Coverage with Unsecured.");
  }

  @Test
  void jsonRawValuesKeepTheirTypeAndScale() {
    store.saveJson(
        JsonEvidence.RUN, RUN_ID, List.of(JsonEvidence.read(JsonEvidence.TYPED, List.of())));

    assertThat(batch(JdbcRejectedRecordStore.INSERT_JSON_ISSUE))
        .extracting(issue -> issue.getValue("field"), issue -> issue.getValue("rawValue"))
        .containsExactly(
            tuple("issuer_name", "89400.00"),
            tuple("issuer_ownership_type", "{\"a\":[1,\"x\"]}"),
            tuple("coupon_type", "true"),
            tuple("listing_date", "\"31-02-2020\""));
    assertThat(batch(JdbcRejectedRecordStore.INSERT_JSON_RECORD))
        .extracting(record -> record.getValue("disposition"))
        .containsExactly("FIELD_REJECTED", "FIELD_REJECTED", "FIELD_REJECTED", "ENTRY_SKIPPED");
  }

  @Test
  void jsonRequestWithoutRejectionsSendsNothing() {
    long errorCount =
        store.saveJson(
            JsonEvidence.RUN,
            RUN_ID,
            List.of(
                JsonEvidence.read(
                    "{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"Simple\"}}}",
                    List.of())));

    assertThat(errorCount).isZero();
    verifyNoInteractions(jdbc);
  }

  private SqlParameterSource[] batch(String sql) {
    ArgumentCaptor<SqlParameterSource[]> rows = ArgumentCaptor.forClass(SqlParameterSource[].class);
    verify(jdbc).batchUpdate(eq(sql), rows.capture());
    return rows.getValue();
  }
}
