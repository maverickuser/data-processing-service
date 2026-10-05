package com.bondplatform.dataprocessing.shared.application;

/** The metrics the service records (LLD section 23.8), with their CloudWatch names and units. */
public enum Metric {

  /** One per finished attempt, by dataset and the job's status after it. */
  JOB_OUTCOME("JobOutcome", "Count"),

  /** How long an attempt ran, from its start to its end, by dataset. */
  RUN_DURATION("RunDuration", "Milliseconds");

  private final String metricName;
  private final String unit;

  Metric(String metricName, String unit) {
    this.metricName = metricName;
    this.unit = unit;
  }

  /** Returns the metric's name in CloudWatch. */
  public String metricName() {
    return metricName;
  }

  /** Returns the metric's CloudWatch unit. */
  public String unit() {
    return unit;
  }
}
