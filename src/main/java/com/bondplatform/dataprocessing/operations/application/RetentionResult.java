package com.bondplatform.dataprocessing.operations.application;

/** How many rows one retention cleanup deleted. */
public record RetentionResult(long validationIssues, long rejectedRecords, long outboxEvents) {}
