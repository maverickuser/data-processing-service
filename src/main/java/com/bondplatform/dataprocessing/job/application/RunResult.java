package com.bondplatform.dataprocessing.job.application;

import java.time.Duration;

/**
 * What a worker should do with the queue message after running a job's attempt.
 *
 * @param disposition whether the message is done with, and why
 * @param retryDelay for a message reported as failed, how long the queue should wait before
 *     delivering it again; zero otherwise
 */
public record RunResult(Disposition disposition, Duration retryDelay) {

  /** The job finished, successfully or not. */
  public static final RunResult FINISHED = new RunResult(Disposition.FINISHED, Duration.ZERO);

  /** The job had already finished, so the message was a redelivery. */
  public static final RunResult ALREADY_FINISHED =
      new RunResult(Disposition.ALREADY_FINISHED, Duration.ZERO);

  /** No job has this ID; the message can never succeed. */
  public static final RunResult UNKNOWN_JOB = new RunResult(Disposition.UNKNOWN_JOB, Duration.ZERO);

  /**
   * The last attempt failed temporarily and the job is failed. The message is reported as failed
   * with no delay, so the queue's redrive policy moves it to the dead-letter queue at once and the
   * next job of its group can run.
   */
  public static final RunResult ATTEMPTS_EXHAUSTED =
      new RunResult(Disposition.ATTEMPTS_EXHAUSTED, Duration.ZERO);

  /** Rejects a negative delay. */
  public RunResult {
    if (retryDelay.isNegative()) {
      throw new IllegalArgumentException("Retry delay must not be negative");
    }
  }

  /** The attempt failed temporarily: deliver the message again after the delay. */
  public static RunResult retryAfter(Duration delay) {
    return new RunResult(Disposition.RETRY, delay);
  }

  /** Returns whether the queue message is done with and should be deleted. */
  public boolean acknowledgesMessage() {
    return disposition.acknowledgesMessage;
  }

  /** Why a message is done with, or why it is not. */
  public enum Disposition {
    FINISHED(true),
    ALREADY_FINISHED(true),
    UNKNOWN_JOB(true),
    RETRY(false),
    ATTEMPTS_EXHAUSTED(false);

    private final boolean acknowledgesMessage;

    Disposition(boolean acknowledgesMessage) {
      this.acknowledgesMessage = acknowledgesMessage;
    }
  }
}
