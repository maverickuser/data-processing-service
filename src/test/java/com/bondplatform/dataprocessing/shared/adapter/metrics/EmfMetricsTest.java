package com.bondplatform.dataprocessing.shared.adapter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.application.Metric;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmfMetricsTest {

  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final EmfMetrics metrics =
      new EmfMetrics(
          new PrintStream(bytes, true, StandardCharsets.UTF_8), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void writesOneEmbeddedMetricFormatLinePerValue() {
    metrics.record(
        Metric.JOB_OUTCOME, 1, Map.of("Outcome", "COMPLETED", "Dataset", "urn:x:dataset:bse"));

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertThat(output).endsWith(System.lineSeparator()).doesNotContain("\n{");
    JsonNode line = JsonMapper.builder().build().readTree(output);
    JsonNode aws = line.get("_aws");
    assertThat(aws.get("Timestamp").asLong()).isEqualTo(NOW.toEpochMilli());
    JsonNode directive = aws.get("CloudWatchMetrics").get(0);
    assertThat(directive.get("Namespace").asString()).isEqualTo("BondPlatform/DataProcessing");
    assertThat(directive.get("Dimensions").toString())
        .isEqualTo("[[\"Dataset\",\"Outcome\"],[\"Outcome\"]]");
    assertThat(directive.get("Metrics")).hasSize(1);
    assertThat(directive.get("Metrics").get(0).get("Name").asString()).isEqualTo("JobOutcome");
    assertThat(directive.get("Metrics").get(0).get("Unit").asString()).isEqualTo("Count");
    assertThat(line.get("Dataset").asString()).isEqualTo("urn:x:dataset:bse");
    assertThat(line.get("Outcome").asString()).isEqualTo("COMPLETED");
    assertThat(line.get("JobOutcome").asLong()).isOne();
  }

  @Test
  void durationIsPublishedByDatasetAndOverAllDatasets() {
    metrics.record(Metric.RUN_DURATION, 1500, Map.of("Dataset", "urn:x:dataset:bse"));

    JsonNode line = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
    assertThat(line.get("_aws").get("CloudWatchMetrics").get(0).get("Dimensions").toString())
        .isEqualTo("[[\"Dataset\"],[]]");
    assertThat(line.get("RunDuration").asLong()).isEqualTo(1500);
  }
}
