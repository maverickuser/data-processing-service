package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SecurityDetailsRequestTest {

  /** U-OBX-01. */
  @Test
  void cloudEventHasTheExactEnvelopeAndData() {
    SecurityDetailsRequest request =
        new SecurityDetailsRequest(
            UUID.fromString("bfed74ba-84ee-45b6-8a7a-50fbb53a0cbb"),
            URI.create("urn:bond-platform:structured-file-processing"),
            Isin.of("INE831R08076"),
            Instant.parse("2026-09-26T10:00:00Z"));

    JsonNode event = JsonMapper.builder().build().readTree(request.cloudEvent());

    JsonNode expected =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {
                  "specversion": "1.0",
                  "id": "bfed74ba-84ee-45b6-8a7a-50fbb53a0cbb",
                  "source": "urn:bond-platform:structured-file-processing",
                  "type": "com.bondplatform.data.pull.requested.v1",
                  "subject": "isin/INE831R08076",
                  "time": "2026-09-26T10:00:00Z",
                  "datacontenttype": "application/json",
                  "data": {
                    "schema_version": 1,
                    "event_type": "nsdl-bond-data",
                    "inputs": {"isin_code": "INE831R08076"}
                  }
                }
                """);
    assertThat(event).isEqualTo(expected);
    assertThat(event.get("data").isObject()).isTrue();
    assertThat(event.get("data").get("schema_version").isInt()).isTrue();
  }
}
