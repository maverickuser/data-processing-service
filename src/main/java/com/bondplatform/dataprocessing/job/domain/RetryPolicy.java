package com.bondplatform.dataprocessing.job.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How often, and after how long, a job is tried again after a temporary failure (LLD sections 8.3
 * and 21).
 *
 * <p>A job gets one attempt more than there are delays. The agreed policy is three attempts: the
 * second one minute after the first fails, the third five minutes after the second fails. A
 * permanent failure is never retried.
 *
 * @param delays the wait after each failed attempt but the last, in order
 */
public record RetryPolicy(List<Duration> delays) {

  /** The agreed policy: retries after 1 minute and then 5 minutes, three attempts in all. */
  public static final RetryPolicy STANDARD =
      new RetryPolicy(List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)));

  /**
   * Copies the delays.
   *
   * @throws IllegalArgumentException if a delay is negative
   */
  public RetryPolicy {
    delays = List.copyOf(delays);
    if (delays.stream().anyMatch(Duration::isNegative)) {
      throw new IllegalArgumentException("Retry delays must not be negative");
    }
  }

  /** Returns how many attempts a job gets in all. */
  public int maxAttempts() {
    return delays.size() + 1;
  }

  /**
   * Returns how long to wait before the next attempt after this attempt failed temporarily, or
   * empty when it was the last attempt.
   *
   * @param attemptNumber the failed attempt's number, from 1
   */
  public Optional<Duration> delayAfterFailedAttempt(int attemptNumber) {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("Attempt numbers start at 1");
    }
    return attemptNumber <= delays.size()
        ? Optional.of(delays.get(attemptNumber - 1))
        : Optional.empty();
  }
}
