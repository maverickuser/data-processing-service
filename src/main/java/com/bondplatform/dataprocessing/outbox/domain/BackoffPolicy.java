package com.bondplatform.dataprocessing.outbox.domain;

import java.time.Duration;

/**
 * How long to wait before trying to deliver an outbox event again (LLD section 21).
 *
 * <p>The wait doubles after each failed attempt, from 10 seconds, and never exceeds 5 minutes.
 * Delivery is retried indefinitely; nothing here gives up.
 */
public final class BackoffPolicy {

  /** The wait after the first failed attempt. */
  public static final Duration FIRST_DELAY = Duration.ofSeconds(10);

  /** The longest wait between attempts. */
  public static final Duration MAX_DELAY = Duration.ofMinutes(5);

  // 10 s doubled 5 times is 320 s, already past the cap; more doublings change nothing.
  private static final int MAX_DOUBLINGS = 5;

  private BackoffPolicy() {}

  /**
   * Returns the wait before the next attempt.
   *
   * @param failedAttempts how many attempts have failed, including the one just made; at least 1
   * @throws IllegalArgumentException if no attempt has failed
   */
  public static Duration delayAfter(int failedAttempts) {
    if (failedAttempts < 1) {
      throw new IllegalArgumentException("At least one attempt must have failed");
    }
    Duration delay = FIRST_DELAY.multipliedBy(1L << Math.min(failedAttempts - 1, MAX_DOUBLINGS));
    return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
  }
}
