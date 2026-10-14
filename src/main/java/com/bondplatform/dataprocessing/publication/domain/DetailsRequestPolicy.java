package com.bondplatform.dataprocessing.publication.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Decides when a traded security's details are requested from the fetch service again (decisions
 * 0005 and 0008).
 *
 * <p>A security a bhavcopy creates is always requested. An existing security is requested again
 * while any of the details the metrics count is missing after the bhavcopy: its coupon rate, cash
 * flows, current ratings, or collateral status. It is requested at most {@link #MAX_REQUESTS} times
 * in all, the first included, and at least {@link #MIN_INTERVAL} after the previous request.
 */
public final class DetailsRequestPolicy {

  /** The most requests ever made for one security: the first, then two retries. */
  public static final int MAX_REQUESTS = 3;

  /** The least time between two requests for one security. */
  public static final Duration MIN_INTERVAL = Duration.ofDays(1);

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
    return detailsMissing(security, couponSupplied)
        && security.detailsRequests() < MAX_REQUESTS
        && olderThan(security.lastDetailsRequest(), MIN_INTERVAL, now);
  }

  private static boolean detailsMissing(LockedSecurity security, boolean couponSupplied) {
    boolean couponMissing = !couponSupplied && security.coupon().rate() == null;
    return couponMissing
        || !security.hasCashFlows()
        || !security.hasCurrentRatings()
        || !security.hasCollateralStatus();
  }

  /** Returns whether {@code time} is at least {@code age} before {@code now}; no time is old. */
  private static boolean olderThan(@Nullable Instant time, Duration age, Instant now) {
    return time == null || !time.plus(age).isAfter(now);
  }
}
