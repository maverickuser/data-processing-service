package com.bondplatform.dataprocessing.job.adapter.persistence;

import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.job.domain.ManifestLocation;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest;
import com.bondplatform.dataprocessing.job.domain.NewIngestionRequest.PinnedContractVersions;
import com.bondplatform.dataprocessing.job.domain.OrderingGroup;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.time.Instant;
import java.util.UUID;

/** Builds ingestion requests for tests. */
final class IngestionRequests {

  static final Instant SUBMITTED_AT = Instant.parse("2026-09-27T14:30:00Z");

  private IngestionRequests() {}

  /** Returns an NSDL request whose identity is derived from the given key and event ID. */
  static NewIngestionRequest nsdl(UUID id, String idempotencyKey, String eventId) {
    return new NewIngestionRequest(
        new JobId(id),
        idempotencyKey,
        "sha256:payload-" + idempotencyKey,
        new DatasetUrn("urn:bond-platform:dataset:nsdl-security"),
        "isin/INE121A07QY9",
        OrderingGroup.forIsin(Isin.of("INE121A07QY9")),
        "urn:bond-platform:service:data-fetch-service",
        eventId,
        idempotencyKey,
        "{\"isin_code\": \"INE121A07QY9\"}",
        new ManifestLocation("data-fetch-service-artifacts", "runs/run_202/manifest.json", null),
        "sha256:fingerprint",
        "{\"specversion\": \"1.0\", \"id\": \"" + eventId + "\"}",
        new PinnedContractVersions(
            new ContractId("nsdl-security-json", "v1"),
            "sha256:source",
            new ContractId("nsdl-security-mapping", "v2"),
            "sha256:mapping"),
        SUBMITTED_AT);
  }
}
