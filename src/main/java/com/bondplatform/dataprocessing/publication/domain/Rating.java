package com.bondplatform.dataprocessing.publication.domain;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One credit-rating observation of a security.
 *
 * <p>The source category is part of the observation's identity: the same rating seen in the current
 * list and in the earlier list are two entries.
 *
 * @param sourceCategory which source list the observation came from
 */
public record Rating(
    SourceCategory sourceCategory,
    @Nullable String ratingAgencyName,
    @Nullable String rating,
    @Nullable String outlook,
    @Nullable String ratingAction,
    @Nullable LocalDate ratingDate,
    @Nullable LocalDate ratingChangeDate,
    @Nullable LocalDate verificationDate) {

  /** Rejects an observation without its source category. */
  public Rating {
    Objects.requireNonNull(sourceCategory, "sourceCategory");
  }

  /**
   * The source list a rating was observed in. It is a label for where the observation came from,
   * not proof that the rating is still in effect.
   */
  public enum SourceCategory {
    CURRENT,
    EARLIER
  }
}
