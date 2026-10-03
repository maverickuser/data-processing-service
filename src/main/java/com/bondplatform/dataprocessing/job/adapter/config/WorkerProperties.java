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
 *     when not set. Tests shorten them; production leaves them unset.
 */
@ConfigurationProperties("data-processing.worker")
public record WorkerProperties(List<Duration> retryDelays) {

  /** Uses the agreed delays when none are configured. */
  public WorkerProperties(@Nullable List<Duration> retryDelays) {
    this.retryDelays =
        retryDelays == null || retryDelays.isEmpty()
            ? RetryPolicy.STANDARD.delays()
            : List.copyOf(retryDelays);
  }
}
