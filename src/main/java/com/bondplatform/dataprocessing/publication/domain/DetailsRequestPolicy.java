package com.bondplatform.dataprocessing.publication.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Decides when a traded security's details are requested from the fetch service again (decision
 * 0005).
 *
 * <p>A security a bhavcopy creates is always requested. An existing security is requested again in
 * two cases:
 *
 * <ul>
 *   <li>its coupon rate is still unknown after the bhavcopy: at most {@link #MAX_REQUESTS} times in
 *       all, and at least {@link #MIN_INTERVAL} after the previous request;
 *   <li>its details are stale: neither the security nor its latest request is newer than {@link
 *       #REFRESH_AFTER}, so new cash flows can be appended. This refresh has no limit on count.
 * </ul>
 */
public final class DetailsRequestPolicy {

  /** The most requests ever made for one security, the first one included. */
  public static final int MAX_REQUESTS = 3;

  /** The least time between two requests for one security. */
  public static final Duration MIN_INTERVAL = Duration.ofDays(1);

  /** How old both the security's last change and its last request must be for a refresh. */
  public static final Duration REFRESH_AFTER = Duration.ofDays(30);

  private DetailsRequestPolicy() {}

  /**
   * Returns whether an existing security is requested again.
   *
   * @param security the security as locked, before this bhavcopy's coupon rates were stored
   * @param couponSupplied whether this bhavcopy reports a coupon rate for the security
   * @param now the publication time
   */
  public static boolean requestsAgain(
      LockedSecurity security, boolean couponSupplied, Instant now) {
    return couponStillUnknown(security, couponSupplied, now) || stale(security, now);
  }

  private static boolean couponStillUnknown(
      LockedSecurity security, boolean couponSupplied, Instant now) {
    if (couponSupplied || security.coupon().rate() != null) {
      return false;
    }
    return security.detailsRequests() < MAX_REQUESTS
        && olderThan(security.lastDetailsRequest(), MIN_INTERVAL, now);
  }

  private static boolean stale(LockedSecurity security, Instant now) {
    return olderThan(security.updatedAt(), REFRESH_AFTER, now)
        && olderThan(security.lastDetailsRequest(), REFRESH_AFTER, now);
  }

  /** Returns whether {@code time} is at least {@code age} before {@code now}; no time is old. */
  private static boolean olderThan(@Nullable Instant time, Duration age, Instant now) {
    return time == null || !time.plus(age).isAfter(now);
  }
}
