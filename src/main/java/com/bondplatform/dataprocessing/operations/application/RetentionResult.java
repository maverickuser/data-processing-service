package com.bondplatform.dataprocessing.operations.application;

/**
 * How many rows one retention cleanup deleted directly. Issues removed with their rejected record
 * by the cascade are not counted.
 */
public record RetentionResult(long validationIssues, long rejectedRecords, long outboxEvents) {}
