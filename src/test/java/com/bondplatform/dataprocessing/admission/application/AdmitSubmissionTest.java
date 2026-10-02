package com.bondplatform.dataprocessing.admission.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.admission.SubmissionEvents;
import com.bondplatform.dataprocessing.contract.adapter.config.ClasspathContractCatalog;
import com.bondplatform.dataprocessing.contract.adapter.config.ContractProperties.ContractPair;
import com.bondplatform.dataprocessing.contract.adapter.yaml.YamlContractLoader;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.application.IngestionRequestRepository;
import com.bondplatform.dataprocessing.job.domain.AcceptedRequest;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Test case U-ADM-02, with in-memory stores. */
class AdmitSubmissionTest {

  private static final Instant NOW = Instant.parse("2026-09-27T14:31:00Z");
  private static final ContractRegistry CONTRACTS =
      new ClasspathContractCatalog(
              new YamlContractLoader(), new ContractValidator(RuleRegistry.standard()))
          .load(
              List.of(
                  new ContractPair("bse-debt-bhavcopy-csv-v1", "bse-debt-bhavcopy-mapping-v1"),
                  new ContractPair("nsdl-security-json-v1", "nsdl-security-mapping-v1")));

  private final InMemoryRequests requests = new InMemoryRequests();
  private final List<NewOutboxEvent> outbox = new ArrayList<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AdmitSubmission admitSubmission =
      new AdmitSubmission(
          CONTRACTS,
          requests,
          outbox::add,
          Clock.fixed(NOW, ZoneOffset.UTC),
          () -> new UUID(0, nextId.getAndIncrement()));

  @Test
  void acceptsNewSubmissionWithPinnedContractsAndQueuesIt() {
    Map<String, Object> event = SubmissionEvents.nsdl("run_202", "INE121A07QY9");

    AdmissionReceipt receipt = admit(event);

    assertThat(receipt.jobId().value()).isEqualTo(new UUID(0, 1));
    assertThat(receipt.eventId()).isEqualTo("urn:bond-platform:submission:run_202");
    assertThat(receipt.runId()).isEqualTo("run_202");
    assertThat(receipt.createdAt()).isEqualTo(NOW);
    NewIngestionRequest stored = requests.inserted.get(0);
    assertThat(stored.idempotencyKey()).isEqualTo("run_202");
    assertThat(stored.payloadHash()).startsWith("sha256:");
    assertThat(stored.dataset().value()).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(stored.subject()).isEqualTo("isin/INE121A07QY9");
    assertThat(stored.orderingGroup().value()).isEqualTo("isin:INE121A07QY9");
    assertThat(stored.inputsJson()).isEqualTo("{\"isin_code\":\"INE121A07QY9\"}");
    assertThat(stored.manifest().key()).isEqualTo("runs/run_202/manifest.json");
    assertThat(stored.submissionEventJson()).startsWith("{\"data\":{").contains("\"specversion\"");
    assertThat(stored.contracts().source().name()).isEqualTo("nsdl-security-json-v1");
    assertThat(stored.contracts().mapping().name()).isEqualTo("nsdl-security-mapping-v1");
    assertThat(stored.contracts().sourceHash()).startsWith("sha256:");
    assertThat(stored.contracts().mappingHash()).isNotEqualTo(stored.contracts().sourceHash());
    assertThat(stored.submittedAt()).isEqualTo(NOW);
    assertThat(requests.locked).containsExactly(new OrderingGroup("isin:INE121A07QY9"));
    assertThat(outbox)
        .containsExactly(
            new NewOutboxEvent(
                new UUID(0, 2),
                OutboxDestination.FILE_PROCESSING,
                "isin:INE121A07QY9",
                1L,
                "{\"jobId\":\"00000000-0000-0000-0000-000000000001\"}",
                NOW));
  }

  @Test
  void bseSubmissionIsGroupedByTradeDateAndPinsTheBseContracts() {
    admit(SubmissionEvents.bse("run_101", "2026-09-21"));

    NewIngestionRequest stored = requests.inserted.get(0);
    assertThat(stored.orderingGroup().value()).isEqualTo("trade-date:2026-09-21");
    assertThat(stored.contracts().source().name()).isEqualTo("bse-debt-bhavcopy-csv-v1");
    assertThat(stored.inputsJson())
        .isEqualTo("{\"exchangeName\":\"BSE\",\"tradeDate\":\"2026-09-21\"}");
  }

  // U-ADM-02
  @Test
  void replayReturnsTheOriginalReceiptAndCreatesNothing() {
    AdmissionReceipt first = admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    AdmissionReceipt replay = admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(replay).isEqualTo(first);
    assertThat(requests.inserted).hasSize(1);
    assertThat(outbox).hasSize(1);
  }

  // U-ADM-02
  @Test
  void replayIsRecognisedWhateverTheKeyOrderOfTheBody() {
    AdmissionReceipt first = admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> reordered = new TreeMap<>(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(admit(reordered)).isEqualTo(first);
  }

  // U-ADM-02
  @Test
  void sameKeyWithDifferentContentIsConflict() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> changed = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    changed.put("time", "2026-09-27T14:30:01Z");

    assertThatThrownBy(() -> admit(changed))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("run_202");
    assertThat(requests.inserted).hasSize(1);
  }

  // U-ADM-02
  @Test
  void addedExtensionAttributeMakesItDifferentEvent() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> withExtension = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    withExtension.put("traceparent", "00-abc-def-01");

    assertThatThrownBy(() -> admit(withExtension)).isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void sameEventIdentityUnderAnotherKeyIsConflict() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    Map<String, Object> otherRun = SubmissionEvents.nsdl("run_203", "INE121A07QY9");
    otherRun.put("id", "urn:bond-platform:submission:run_202");

    assertThatThrownBy(() -> admit(otherRun)).isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void keyAndEventIdentityOfTwoEarlierSubmissionsIsConflict() {
    admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));
    admit(SubmissionEvents.nsdl("run_203", "INE121A07QY9"));
    Map<String, Object> mixed = SubmissionEvents.nsdl("run_202", "INE121A07QY9");
    mixed.put("id", "urn:bond-platform:submission:run_203");

    assertThatThrownBy(() -> admit(mixed)).isInstanceOf(IdempotencyConflictException.class);
  }

  @Test
  void submissionStoredByAnotherTransactionMeanwhileIsRecognisedAsReplay() {
    requests.hideNextLookup = true;
    requests.existingBeforeInsert = SubmissionEvents.nsdl("run_202", "INE121A07QY9");

    AdmissionReceipt receipt = admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9"));

    assertThat(receipt.runId()).isEqualTo("run_202");
    assertThat(outbox).isEmpty();
  }

  @Test
  void submissionNeitherStoredNorFoundIsAnError() {
    requests.refuseInserts = true;

    assertThatIllegalStateException()
        .isThrownBy(() -> admit(SubmissionEvents.nsdl("run_202", "INE121A07QY9")))
        .withMessageContaining("neither stored nor found");
  }

  private AdmissionReceipt admit(Map<String, Object> event) {
    return admitSubmission.admit(SubmissionEvents.submissionOf(event), event);
  }

  /** Keeps requests in a list and applies the same uniqueness rules as the database. */
  private static final class InMemoryRequests implements IngestionRequestRepository {

    private final List<NewIngestionRequest> inserted = new ArrayList<>();
    private final List<AcceptedRequest> stored = new ArrayList<>();
    private final List<OrderingGroup> locked = new ArrayList<>();
    private boolean hideNextLookup;
    private boolean refuseInserts;
    private Map<String, Object> existingBeforeInsert = Map.of();

    @Override
    public Optional<AcceptedRequest> insertIfAbsent(NewIngestionRequest request) {
      if (!existingBeforeInsert.isEmpty()) {
        // Simulates another transaction committing the same submission just before this insert.
        Map<String, Object> event = existingBeforeInsert;
        existingBeforeInsert = Map.of();
        store(
            new NewIngestionRequest(
                request.id(),
                request.idempotencyKey(),
                request.payloadHash(),
                request.dataset(),
                request.subject(),
                request.orderingGroup(),
                request.eventSource(),
                String.valueOf(event.get("id")),
                request.runId(),
                request.inputsJson(),
                request.manifest(),
                request.datasetFingerprint(),
                request.submissionEventJson(),
                request.contracts(),
                request.submittedAt()));
        return Optional.empty();
      }
      boolean exists =
          stored.stream()
              .anyMatch(
                  existing ->
                      existing.idempotencyKey().equals(request.idempotencyKey())
                          || (existing.eventSource().equals(request.eventSource())
                              && existing.eventId().equals(request.eventId())));
      if (exists || refuseInserts) {
        return Optional.empty();
      }
      inserted.add(request);
      return Optional.of(store(request));
    }

    private AcceptedRequest store(NewIngestionRequest request) {
      AcceptedRequest accepted =
          new AcceptedRequest(
              request.id(),
              request.idempotencyKey(),
              request.payloadHash(),
              request.eventSource(),
              request.eventId(),
              request.runId(),
              request.orderingGroup(),
              stored.size() + 1L,
              JobStatus.QUEUED,
              request.submittedAt());
      stored.add(accepted);
      return accepted;
    }

    @Override
    public void lockOrderingGroup(OrderingGroup group) {
      locked.add(group);
    }

    @Override
    public List<AcceptedRequest> findByIdempotencyKeyOrEvent(
        String idempotencyKey, String eventSource, String eventId) {
      if (hideNextLookup) {
        hideNextLookup = false;
        return List.of();
      }
      return stored.stream()
          .filter(
              request ->
                  request.idempotencyKey().equals(idempotencyKey)
                      || (request.eventSource().equals(eventSource)
                          && request.eventId().equals(eventId)))
          .toList();
    }
  }
}
