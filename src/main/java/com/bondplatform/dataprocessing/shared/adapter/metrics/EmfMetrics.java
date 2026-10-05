package com.bondplatform.dataprocessing.shared.adapter.metrics;

import com.bondplatform.dataprocessing.shared.application.Metric;
import com.bondplatform.dataprocessing.shared.application.Metrics;
import java.io.PrintStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes each metric as one CloudWatch embedded metric format line on standard output (LLD section
 * 23.8). Lambda sends standard output to CloudWatch Logs, which turns such lines into metrics; no
 * call to CloudWatch is made, so recording a metric cannot slow a job.
 */
@Component
public class EmfMetrics implements Metrics {

  /** The CloudWatch namespace of every metric the service records. */
  static final String NAMESPACE = "BondPlatform/DataProcessing";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PrintStream out;
  private final Clock clock;

  /** Creates metrics written to standard output. */
  @Autowired
  public EmfMetrics(Clock clock) {
    this(System.out, clock);
  }

  /** Creates metrics written to the given stream. */
  EmfMetrics(PrintStream out, Clock clock) {
    this.out = out;
    this.clock = clock;
  }

  @Override
  public void record(Metric metric, long value, Map<String, String> dimensions) {
    Map<String, String> sorted = new TreeMap<>(dimensions);
    Map<String, Object> directive =
        Map.of(
            "Namespace",
            NAMESPACE,
            "Dimensions",
            metric.dimensionSets(),
            "Metrics",
            List.of(Map.of("Name", metric.metricName(), "Unit", metric.unit())));
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("_aws", Map.of("Timestamp", clock.millis(), "CloudWatchMetrics", List.of(directive)));
    line.putAll(sorted);
    line.put(metric.metricName(), value);
    out.println(JSON.writeValueAsString(line));
  }
}
