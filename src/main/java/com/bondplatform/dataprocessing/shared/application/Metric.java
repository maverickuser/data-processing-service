package com.bondplatform.dataprocessing.shared.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The metrics the service records (LLD section 23.8), with their CloudWatch names and units. */
public enum Metric {

  /**
   * One per finished attempt, by dataset and the job's status after it, and by status alone so one
   * alarm covers every dataset.
   */
  JOB_OUTCOME("JobOutcome", "Count", List.of("Dataset", "Outcome"), List.of("Outcome")),

  /** How long an attempt ran, from its start to its end, by dataset and over all datasets. */
  RUN_DURATION("RunDuration", "Milliseconds", List.of("Dataset"), List.of()),

  /** How many stuck jobs a sweep failed, zero included, so an alarm always has data. */
  STUCK_JOBS_FAILED("StuckJobsFailed", "Count", List.of()),

  /** How long the oldest pending outbox event has waited, measured after each sweep. */
  OLDEST_PENDING_OUTBOX_AGE("OldestPendingOutboxAge", "Seconds", List.of());

  private final String metricName;
  private final String unit;

  @SuppressWarnings("ImmutableEnumChecker") // List.copyOf lists cannot be changed
  private final List<List<String>> dimensionSets;

  /** The first dimension set names every dimension; a metric without dimensions has one, empty. */
  @SafeVarargs
  Metric(String metricName, String unit, List<String> recorded, List<String>... coarser) {
    this.metricName = metricName;
    this.unit = unit;
    List<List<String>> sets = new ArrayList<>();
    sets.add(recorded);
    sets.addAll(List.of(coarser));
    this.dimensionSets = List.copyOf(sets);
  }

  /** Returns the dimensions a value of this metric is recorded with. */
  public Set<String> dimensions() {
    return Set.copyOf(dimensionSets.get(0));
  }

  /** Returns the metric's name in CloudWatch. */
  public String metricName() {
    return metricName;
  }

  /**
   * Returns the dimension sets CloudWatch publishes the metric under; a value is recorded with
   * every dimension the first set names.
   */
  public List<List<String>> dimensionSets() {
    return dimensionSets;
  }

  /** Returns the metric's CloudWatch unit. */
  public String unit() {
    return unit;
  }
}
