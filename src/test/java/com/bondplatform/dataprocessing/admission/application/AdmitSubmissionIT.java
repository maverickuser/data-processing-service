package com.bondplatform.dataprocessing.admission.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * Admission against PostgreSQL: the use-case half of I-ADM-01, I-ADM-02, I-ADM-03, and I-ADM-05.
 * The HTTP half of those cases arrives with the submission endpoint.
 */
class AdmitSubmissionIT extends PostgresIntegrationTest {

  private static final int CONCURRENT_CALLERS = 4;

  private static final long WAIT_SECONDS = 20;

  @Autowired private AdmitSubmission admitSubmission;
  @Autowired private DataSource dataSource;

  // I-ADM-01
  @Test
  void storesTheRequestWithPinnedContractsAndItsOutboxEvent() {
    AdmissionReceipt receipt = admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    Map<String, Object> request =
        jdbc.sql(
                """
                SELECT id, idempotency_key, status, ordering_group, acceptance_sequence,
                  source_contract_id, source_contract_version, mapping_contract_id,
                  mapping_contract_version,
                  source_contract_hash LIKE 'sha256:%' AS source_hashed,
                  mapping_contract_hash LIKE 'sha256:%' AS mapping_hashed,
                  payload_hash LIKE 'sha256:%' AS payload_hashed,
                  submission_event ->> 'id' AS event_id,
                  inputs ->> 'isin_code' AS isin
                FROM data_processing.ingestion_requests
                """)
            .query()
            .singleRow();
    assertThat(request)
        .containsEntry("id", receipt.jobId().value())
        .containsEntry("idempotency_key", "run_202")
        .containsEntry("status", "QUEUED")
        .containsEntry("ordering_group", "isin:INE121A07QY9")
        .containsEntry("source_contract_id", "nsdl-security-json")
        .containsEntry("source_contract_version", "v1")
        .containsEntry("mapping_contract_id", "nsdl-security-mapping")
        .containsEntry("mapping_contract_version", "v1")
        .containsEntry("source_hashed", true)
        .containsEntry("mapping_hashed", true)
        .containsEntry("payload_hashed", true)
        .containsEntry("event_id", "urn:bond-platform:submission:run_202")
        .containsEntry("isin", "INE121A07QY9");
    Map<String, Object> event =
        jdbc.sql(
                """
                SELECT destination, message_group, ordering_key, status, payload ->> 'jobId' AS job
                FROM data_processing.outbox_events
                """)
            .query()
            .singleRow();
    assertThat(event)
        .containsEntry("destination", "FILE_PROCESSING")
        .containsEntry("message_group", "isin:INE121A07QY9")
        .containsEntry("ordering_key", request.get("acceptance_sequence"))
        .containsEntry("status", "PENDING")
        .containsEntry("job", receipt.jobId().toString());
  }

  // I-ADM-01
  @Test
  void requestIsNotStoredWhenItsOutboxEventCannotBe() {
    jdbc.sql(
            """
            CREATE FUNCTION data_processing.refuse_outbox_event() RETURNS trigger
            LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox unavailable'; END $$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER refuse_outbox_event BEFORE INSERT ON data_processing.outbox_events
            FOR EACH ROW EXECUTE FUNCTION data_processing.refuse_outbox_event()
            """)
        .update();
    try {
      assertThatThrownBy(() -> admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9")))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbc.sql("DROP FUNCTION data_processing.refuse_outbox_event() CASCADE").update();
    }

    assertThat(count("ingestion_requests")).isZero();
    assertThat(count("outbox_events")).isZero();
    assertThat(admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9")).runId())
        .isEqualTo("run_202");
  }

  // I-ADM-02
  @Test
  void replayReturnsTheSameReceiptEvenAfterTheJobIsTerminal() {
    AdmissionReceipt first = admit(SubmissionEvents.bse("run_101", "2026-09-21"));
    jdbc.sql("UPDATE data_processing.ingestion_requests SET status = 'COMPLETED'").update();

    AdmissionReceipt replay = admit(SubmissionEvents.bse("run_101", "2026-09-21"));

    assertThat(replay).isEqualTo(first);
    assertThat(count("ingestion_requests")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  // I-ADM-03
  @Test
  void keyReusedWithDifferentContentIsConflictAndStoresNothing() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> changed = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    changed.put("time", "2026-09-27T14:30:01Z");

    assertThatThrownBy(() -> admit(changed)).isInstanceOf(IdempotencyConflictException.class);

    assertThat(count("ingestion_requests")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  // I-ADM-03
  @Test
  void eventIdentityReusedFromAnotherOrderingGroupIsConflict() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> other = SubmissionEvents.nsdl("run_203", "INE002A08534");
    other.put("id", "urn:bond-platform:submission:run_202");

    assertThatThrownBy(() -> admit(other)).isInstanceOf(IdempotencyConflictException.class);

    assertThat(count("ingestion_requests")).isEqualTo(1);
  }

  // I-ADM-05
  @Test
  void concurrentIdenticalSubmissionsCreateOneRequest() throws Exception {
    List<AdmissionReceipt> receipts =
        admitAtOnce(caller -> SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(receipts).hasSize(CONCURRENT_CALLERS);
    assertThat(receipts.stream().distinct()).hasSize(1);
    assertThat(count("ingestion_requests")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void concurrentSubmissionsOfOneGroupAreEachQueuedInAcceptanceOrder() throws Exception {
    List<AdmissionReceipt> receipts =
        admitAtOnce(caller -> SubmissionEvents.nsdl("run_30" + caller, "INE121A07QY9"));

    assertThat(receipts.stream().map(AdmissionReceipt::jobId).distinct())
        .hasSize(CONCURRENT_CALLERS);
    List<Map<String, Object>> queued =
        jdbc.sql(
                """
                SELECT r.acceptance_sequence = e.ordering_key AS same_order
                FROM data_processing.ingestion_requests r
                JOIN data_processing.outbox_events e ON e.payload ->> 'jobId' = r.id::text
                """)
            .query()
            .listOfRows();
    assertThat(queued)
        .hasSize(CONCURRENT_CALLERS)
        .allMatch(row -> Boolean.TRUE.equals(row.get("same_order")));
  }

  @Test
  void admissionWaitsWhileAnotherTransactionHoldsItsOrderingGroup() throws Exception {
    try (Connection holder = dataSource.getConnection();
        ExecutorService callers = Executors.newFixedThreadPool(2)) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        lock.setString(1, "isin:INE121A07QY9");
        lock.execute();
      }

      Future<AdmissionReceipt> sameGroup =
          callers.submit(() -> admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9")));
      Future<AdmissionReceipt> otherGroup =
          callers.submit(() -> admit(SubmissionEvents.nsdl("run_203", "INE002A08534")));

      assertThat(otherGroup.get(WAIT_SECONDS, TimeUnit.SECONDS).runId()).isEqualTo("run_203");
      awaitOneAdmissionWaitingForLock();
      assertThat(sameGroup.isDone()).isFalse();
      assertThat(count("ingestion_requests")).isEqualTo(1);

      holder.commit();

      assertThat(sameGroup.get(WAIT_SECONDS, TimeUnit.SECONDS).runId()).isEqualTo("run_202");
      assertThat(count("ingestion_requests")).isEqualTo(2);
    }
  }

  // The validator's half of this is in SubmissionValidatorTest; here: PostgreSQL really refuses it.
  @Test
  void textTheDatabaseCannotStoreIsRefusedByValidationNotByTheDatabase() {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    event.put("id", "urn:bond-platform:submission:run\0_202");

    assertThatThrownBy(() -> SubmissionEvents.submissionOf(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NUL");
    assertThatThrownBy(
            () ->
                jdbc.sql("SELECT CAST(:json AS jsonb)")
                    .param("json", "{\"id\":\"a\\u0000b\"}")
                    .query()
                    .singleRow())
        .isInstanceOf(DataAccessException.class);
  }

  /** Polls until PostgreSQL reports exactly one session waiting for an advisory lock. */
  private void awaitOneAdmissionWaitingForLock() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      long waiting =
          jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
              .query(Long.class)
              .single();
      if (waiting == 1) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("No admission was waiting for the ordering-group lock");
  }

  private AdmissionReceipt admit(Map<String, Object> event) {
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event);
  }

  /** Admits one event per caller, all released at the same moment on their own connections. */
  private List<AdmissionReceipt> admitAtOnce(EventForCaller events) throws Exception {
    CyclicBarrier start = new CyclicBarrier(CONCURRENT_CALLERS);
    List<Callable<AdmissionReceipt>> calls = new ArrayList<>();
    for (int caller = 0; caller < CONCURRENT_CALLERS; caller++) {
      Map<String, Object> event = events.eventFor(caller);
      calls.add(
          () -> {
            start.await();
            return admit(event);
          });
    }
    List<AdmissionReceipt> receipts = new ArrayList<>();
    try (ExecutorService callers = Executors.newFixedThreadPool(CONCURRENT_CALLERS)) {
      for (Future<AdmissionReceipt> result : callers.invokeAll(calls)) {
        receipts.add(result.get());
      }
    }
    return receipts;
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM data_processing." + table).query(Long.class).single();
  }

  /** Supplies the event a given concurrent caller submits. */
  @FunctionalInterface
  private interface EventForCaller {
    Map<String, Object> eventFor(int caller);
  }
}
