package com.bondplatform.dataprocessing.publication.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides when a traded security's details are requested from the fetch service again (decision
 * 0005).
 *
 * <p>A security a bhavcopy creates is always requested. An existing security is requested again
 * when its coupon rate is still unknown after the bhavcopy, at most {@link #MAX_REQUESTS} times in
 * all and at least {@link #MIN_INTERVAL} after the previous request.
 */
public final class DetailsRequestPolicy {

  /** The most requests ever made for one security, the first one included. */
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
    if (couponSupplied || security.coupon().rate() != null) {
      return false;
    }
    if (security.detailsRequests() >= MAX_REQUESTS) {
      return false;
    }
    Instant last = security.lastDetailsRequest();
    return last == null || !last.plus(MIN_INTERVAL).isAfter(now);
  }
}
