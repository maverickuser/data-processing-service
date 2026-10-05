package com.bondplatform.dataprocessing.operations.application;

import java.time.Instant;

/**
 * When an unfinished job counts as stuck (LLD section 23.3): it has made no progress (submission, a
 * run's start or a run's end) since {@code progressCutoff}, or it is processing its final attempt
 * and that attempt started before {@code finalAttemptCutoff}.
 *
 * @param finalAttempt the number of the last attempt a job may make
 */
public record StuckJobRule(Instant progressCutoff, Instant finalAttemptCutoff, int finalAttempt) {}
