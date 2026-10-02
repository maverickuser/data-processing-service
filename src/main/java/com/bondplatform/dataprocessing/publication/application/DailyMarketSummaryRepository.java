package com.bondplatform.dataprocessing.publication.application;

import com.bondplatform.dataprocessing.publication.domain.DailyMarketSummary;
import java.time.Instant;
import java.util.Collection;

/** Stores daily market summaries. */
public interface DailyMarketSummaryRepository {

  /**
   * Inserts each summary, or replaces the stored summary that has the same security, trade date,
   * and exchange.
   *
   * <p>A replacement overwrites every value, including with {@code null}, and updates the source
   * reference. The creation time of an existing summary is kept. Every security must already exist.
   *
   * @param recordedAt stored as the creation time of new summaries and the update time of all
   */
  void upsertAll(Collection<DailyMarketSummary> summaries, Instant recordedAt);
}
