package com.bondplatform.dataprocessing.job.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.job.application.IngestionRequestRepository;
import com.bondplatform.dataprocessing.job.domain.AcceptedRequest;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The ingestion-request repository against PostgreSQL. */
class IngestionRequestRepositoryIT extends PostgresIntegrationTest {

  private static final String SOURCE = "urn:bond-platform:service:data-fetch-service";

  @Autowired private IngestionRequestRepository requests;

  @Test
  void storesEveryColumnAndReturnsTheQueuedRequest() {
    UUID id = UUID.randomUUID();

    Optional<AcceptedRequest> stored =
        requests.insertIfAbsent(IngestionRequests.nsdl(id, "run_202", "event-202"));

    assertThat(stored).isPresent();
    AcceptedRequest request = stored.orElseThrow();
    assertThat(request.id().value()).isEqualTo(id);
    assertThat(request.idempotencyKey()).isEqualTo("run_202");
    assertThat(request.payloadHash()).isEqualTo("sha256:payload-run_202");
    assertThat(request.eventSource()).isEqualTo(SOURCE);
    assertThat(request.eventId()).isEqualTo("event-202");
    assertThat(request.runId()).isEqualTo("run_202");
    assertThat(request.orderingGroup().value()).isEqualTo("isin:INE121A07QY9");
    assertThat(request.acceptanceSequence()).isPositive();
    assertThat(request.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(request.submittedAt()).isEqualTo(IngestionRequests.SUBMITTED_AT);

    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT dataset_urn, subject, inputs::text AS inputs, manifest_bucket, manifest_key,
                  manifest_version_id, dataset_fingerprint, submission_event ->> 'id' AS event,
                  source_contract_id, source_contract_version, source_contract_hash,
                  mapping_contract_id, mapping_contract_version, mapping_contract_hash,
                  attempt_count, error_count
                FROM data_processing.ingestion_requests
                """)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("dataset_urn", "urn:bond-platform:dataset:nsdl-security")
        .containsEntry("subject", "isin/INE121A07QY9")
        .containsEntry("inputs", "{\"isin_code\": \"INE121A07QY9\"}")
        .containsEntry("manifest_bucket", "data-fetch-service-artifacts")
        .containsEntry("manifest_key", "runs/run_202/manifest.json")
        .containsEntry("manifest_version_id", null)
        .containsEntry("dataset_fingerprint", "sha256:fingerprint")
        .containsEntry("event", "event-202")
        .containsEntry("source_contract_id", "nsdl-security-json")
        .containsEntry("source_contract_version", "v1")
        .containsEntry("source_contract_hash", "sha256:source")
        .containsEntry("mapping_contract_id", "nsdl-security-mapping")
        .containsEntry("mapping_contract_version", "v2")
        .containsEntry("mapping_contract_hash", "sha256:mapping")
        .containsEntry("attempt_count", 0)
        .containsEntry("error_count", 0);
  }

  @Test
  void laterRequestsHaveLargerAcceptanceSequence() {
    AcceptedRequest first = insert("run_1", "event-1");
    AcceptedRequest second = insert("run_2", "event-2");

    assertThat(second.acceptanceSequence()).isGreaterThan(first.acceptanceSequence());
  }

  @Test
  void sameIdempotencyKeyIsNotStoredTwice() {
    insert("run_1", "event-1");

    assertThat(requests.insertIfAbsent(request("run_1", "event-other"))).isEmpty();
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void sameEventIdentityIsNotStoredTwice() {
    insert("run_1", "event-1");

    assertThat(requests.insertIfAbsent(request("run_other", "event-1"))).isEmpty();
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void findsNothingOneOrTwoRequestsByKeyOrEvent() {
    AcceptedRequest first = insert("run_1", "event-1");
    AcceptedRequest second = insert("run_2", "event-2");

    assertThat(requests.findByIdempotencyKeyOrEvent("run_9", SOURCE, "event-9")).isEmpty();
    assertThat(requests.findByIdempotencyKeyOrEvent("run_1", SOURCE, "event-1"))
        .containsExactly(first);
    assertThat(requests.findByIdempotencyKeyOrEvent("run_1", SOURCE, "event-2"))
        .containsExactly(first, second);
    assertThat(requests.findByIdempotencyKeyOrEvent("run_9", "other-source", "event-1")).isEmpty();
  }

  private AcceptedRequest insert(String idempotencyKey, String eventId) {
    return requests.insertIfAbsent(request(idempotencyKey, eventId)).orElseThrow();
  }

  private static NewIngestionRequest request(String idempotencyKey, String eventId) {
    return IngestionRequests.nsdl(UUID.randomUUID(), idempotencyKey, eventId);
  }

  private long count() {
    return jdbc.sql("SELECT count(*) FROM data_processing.ingestion_requests")
        .query(Long.class)
        .single();
  }
}
