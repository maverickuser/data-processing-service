package com.bondplatform.dataprocessing.job.adapter.config;

import com.bondplatform.dataprocessing.job.domain.RetryPolicy;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker settings.
 *
 * @param retryDelays the wait after each failed attempt but the last; the agreed 1 and 5 minutes
 *     when not set. Tests shorten them; production leaves them unset. Their number is fixed.
 */
@ConfigurationProperties("data-processing.worker")
public record WorkerProperties(List<Duration> retryDelays) {

  /**
   * Uses the agreed delays when none are configured.
   *
   * @throws IllegalArgumentException if the number of delays differs from the agreed policy's: it
   *     sets the number of attempts, which must stay three to match the queue's redrive count
   */
  public WorkerProperties(@Nullable List<Duration> retryDelays) {
    if (retryDelays == null || retryDelays.isEmpty()) {
      this.retryDelays = RetryPolicy.STANDARD.delays();
      return;
    }
    if (retryDelays.size() != RetryPolicy.STANDARD.delays().size()) {
      throw new IllegalArgumentException(
          "data-processing.worker.retry-delays needs exactly "
              + RetryPolicy.STANDARD.delays().size()
              + " delays; only their length may change");
    }
    this.retryDelays = List.copyOf(retryDelays);
  }
}
