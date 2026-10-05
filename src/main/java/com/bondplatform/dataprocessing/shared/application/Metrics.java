package com.bondplatform.dataprocessing.shared.application;

import java.util.Map;

/** Records operational metrics (LLD section 23.8). */
public interface Metrics {

  /**
   * Records one value of a metric. The dimensions are few and come from a small, fixed set of
   * values, never from source data or job IDs, so the number of CloudWatch series stays small.
   */
  void record(Metric metric, long value, Map<String, String> dimensions);
}
