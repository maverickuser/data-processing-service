package com.bondplatform.dataprocessing.publication.domain;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a publication needs to know about a security whose row it has locked.
 *
 * @param coupon the stored coupon rate and its source
 * @param detailsRequests how many security-details requests were made for the security
 * @param lastDetailsRequest when the latest one was made, or {@code null} if none was
 * @param updatedAt when the security last changed
 */
public record LockedSecurity(
    StoredCoupon coupon,
    int detailsRequests,
    @Nullable Instant lastDetailsRequest,
    Instant updatedAt) {

  /** Rejects a missing coupon or a negative count. */
  public LockedSecurity {
    Objects.requireNonNull(coupon, "coupon");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (detailsRequests < 0) {
      throw new IllegalArgumentException("Request count must not be negative: " + detailsRequests);
    }
  }
}
