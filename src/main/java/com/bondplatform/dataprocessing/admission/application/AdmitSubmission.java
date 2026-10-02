package com.bondplatform.dataprocessing.admission.application;

import com.bondplatform.dataprocessing.admission.domain.CanonicalJson;
import com.bondplatform.dataprocessing.admission.domain.Submission;
import com.bondplatform.dataprocessing.contract.application.ContractRegistry;
import com.bondplatform.dataprocessing.contract.application.PinnedContracts;
import com.bondplatform.dataprocessing.contract.domain.ContractHash;
import com.bondplatform.dataprocessing.job.application.IngestionRequestRepository;
import com.bondplatform.dataprocessing.job.domain.AcceptedRequest;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a validated submission durably (LLD section 2.1).
 *
 * <p>In one transaction it records the ingestion request with the contract versions pinned for
 * every later attempt, and the outbox event that will queue the job. Only after that commit may the
 * caller answer {@code 202}. Nothing is read from S3 and nothing is sent to a queue here.
 *
 * <p>Admission is idempotent. Presenting the same event with the same key again returns the
 * original receipt and creates nothing. Reusing a key or an event identity with different content
 * is a conflict.
 *
 * <p>The transaction is read committed on purpose: when another transaction stores the same
 * submission first, the lookup that follows the refused insert must see that newly committed row.
 */
@Service
public class AdmitSubmission {

  private final ContractRegistry contracts;
  private final IngestionRequestRepository requests;
  private final OutboxEventStore outbox;
  private final Clock clock;
  private final IdSupplier idSupplier;

  /** Creates the use case. */
  public AdmitSubmission(
      ContractRegistry contracts,
      IngestionRequestRepository requests,
      OutboxEventStore outbox,
      Clock clock,
      IdSupplier idSupplier) {
    this.contracts = contracts;
    this.requests = requests;
    this.outbox = outbox;
    this.clock = clock;
    this.idSupplier = idSupplier;
  }

  /**
   * Accepts the submission, or recognises it as a replay.
   *
   * @param submission the validated submission
   * @param event the whole parsed event the submission was read from; its canonical form is what
   *     identifies a replay, so extension attributes and the time count too
   * @return the receipt of the first acceptance
   * @throws IdempotencyConflictException if the key or event identity was accepted with other
   *     content
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AdmissionReceipt admit(Submission submission, Map<String, Object> event) {
    String canonicalEvent = CanonicalJson.of(event);
    String payloadHash = ContractHash.of(canonicalEvent.getBytes(StandardCharsets.UTF_8));
    // Admissions to one ordering group take turns, so acceptance order is commit order.
    requests.lockOrderingGroup(submission.orderingGroup());

    Optional<AcceptedRequest> replayed = existing(submission, payloadHash);
    if (replayed.isPresent()) {
      return receiptOf(replayed.get());
    }
    Optional<AcceptedRequest> stored =
        requests.insertIfAbsent(newRequest(submission, event, canonicalEvent, payloadHash));
    if (stored.isPresent()) {
      outbox.append(dispatchEvent(stored.get()));
      return receiptOf(stored.get());
    }
    // Another ordering group committed the same key or event identity in between.
    return existing(submission, payloadHash)
        .map(AdmitSubmission::receiptOf)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Submission " + submission.runId() + " was neither stored nor found"));
  }

  /**
   * Returns the request this submission replays, or empty if nothing shares its identities.
   *
   * @throws IdempotencyConflictException if something shares an identity but is not this event
   */
  private Optional<AcceptedRequest> existing(Submission submission, String payloadHash) {
    List<AcceptedRequest> found =
        requests.findByIdempotencyKeyOrEvent(
            submission.runId(), submission.eventSource(), submission.eventId());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    AcceptedRequest request = found.get(0);
    boolean sameSubmission =
        found.size() == 1
            && request.idempotencyKey().equals(submission.runId())
            && request.eventSource().equals(submission.eventSource())
            && request.eventId().equals(submission.eventId())
            && request.payloadHash().equals(payloadHash);
    if (!sameSubmission) {
      throw new IdempotencyConflictException(submission.runId());
    }
    return Optional.of(request);
  }

  private NewIngestionRequest newRequest(
      Submission submission, Map<String, Object> event, String canonicalEvent, String payloadHash) {
    PinnedContracts pinned = contracts.contractsFor(submission.dataset());
    Object data = event.get("data");
    Object inputs = data instanceof Map<?, ?> map ? map.get("inputs") : null;
    return new NewIngestionRequest(
        new JobId(idSupplier.nextId()),
        submission.runId(),
        payloadHash,
        submission.dataset(),
        submission.subject(),
        submission.orderingGroup(),
        submission.eventSource(),
        submission.eventId(),
        submission.runId(),
        CanonicalJson.of(inputs),
        submission.manifest(),
        submission.datasetFingerprint(),
        canonicalEvent,
        new PinnedContractVersions(
            pinned.source().id(), pinned.sourceHash(), pinned.mapping().id(), pinned.mappingHash()),
        Instant.now(clock));
  }

  private NewOutboxEvent dispatchEvent(AcceptedRequest request) {
    return new NewOutboxEvent(
        idSupplier.nextId(),
        OutboxDestination.FILE_PROCESSING,
        request.orderingGroup().value(),
        request.acceptanceSequence(),
        CanonicalJson.of(Map.of("jobId", request.id().toString())),
        request.submittedAt());
  }

  private static AdmissionReceipt receiptOf(AcceptedRequest request) {
    return new AdmissionReceipt(
        request.id(), request.eventId(), request.runId(), request.submittedAt());
  }
}
