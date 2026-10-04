package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.CanonicalJson;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A committed request for a new security's details, sent to the fetch service as a CloudEvent (LLD
 * section 14.2).
 *
 * <p>It records that details were requested, not that they were retrieved. Its identity and time
 * are fixed when the request is stored and never change on delivery retries.
 *
 * @param id the event's identity; the consumer deduplicates by source and id
 * @param source the producer identity, including the environment
 * @param isin the security whose details are requested
 * @param requestedAt when the request was stored
 */
public record SecurityDetailsRequest(UUID id, URI source, Isin isin, Instant requestedAt) {

  /** The fetch service's routing type for a data pull. */
  public static final String TYPE = "com.bondplatform.data.pull.requested.v1";

  /** Rejects a missing part. */
  public SecurityDetailsRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  /** Returns the complete CloudEvent as JSON, with {@code data} as a nested object. */
  public String cloudEvent() {
    return CanonicalJson.of(
        Map.of(
            "specversion",
            "1.0",
            "id",
            id.toString(),
            "source",
            source.toString(),
            "type",
            TYPE,
            "subject",
            "isin/" + isin.value(),
            "time",
            requestedAt.toString(),
            "datacontenttype",
            "application/json",
            "data",
            Map.of(
                "schema_version",
                1,
                "event_type",
                "nsdl-bond-data",
                "inputs",
                Map.of("isin_code", isin.value()))));
  }
}
