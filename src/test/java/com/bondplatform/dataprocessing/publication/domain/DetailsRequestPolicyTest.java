package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Test cases U-DETAILS-01 and U-DETAILS-04. */
class DetailsRequestPolicyTest {

  private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
  private static final Instant DAY_AGO = NOW.minus(Duration.ofDays(1));
  private static final StoredCoupon COUPON =
      new StoredCoupon(Percent.parse("8"), CouponSource.NSDL, null);

  // U-DETAILS-04
  @Test
  void securityMissingAnyOfTheFourDetailsIsAskedAgain() {
    assertThat(ask(StoredCoupon.NONE, true, true, true)).as("no coupon").isTrue();
    assertThat(ask(COUPON, false, true, true)).as("no cash flows").isTrue();
    assertThat(ask(COUPON, true, false, true)).as("no current ratings").isTrue();
    assertThat(ask(COUPON, true, true, false)).as("no collateral status").isTrue();
  }

  // U-DETAILS-04
  @Test
  void securityWithEveryDetailOrCouponInFileIsNotAskedAgain() {
    assertThat(ask(COUPON, true, true, true)).isFalse();
    assertThat(
            DetailsRequestPolicy.requestsAgain(
                new LockedSecurity(StoredCoupon.NONE, 1, DAY_AGO, true, true, true), true, NOW))
        .as("the coupon comes in this bhavcopy")
        .isFalse();
  }

  // U-DETAILS-01
  @Test
  void securityIsAskedAgainTwiceAfterFirstRequestAtMostDaily() {
    assertThat(policy(0, null)).isTrue();
    assertThat(policy(1, DAY_AGO)).isTrue();
    assertThat(policy(2, NOW.minus(Duration.ofDays(5)))).isTrue();
    assertThat(policy(1, DAY_AGO.plusSeconds(1))).as("sooner than a day").isFalse();
    assertThat(policy(3, NOW.minus(Duration.ofDays(30)))).as("two retries used").isFalse();
  }

  @Test
  void negativeRequestCountIsRefused() {
    assertThatThrownBy(() -> new LockedSecurity(StoredCoupon.NONE, -1, null, true, true, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static boolean ask(
      StoredCoupon coupon, boolean cashFlows, boolean currentRatings, boolean collateralStatus) {
    return DetailsRequestPolicy.requestsAgain(
        new LockedSecurity(coupon, 1, DAY_AGO, cashFlows, currentRatings, collateralStatus),
        false,
        NOW);
  }

  private static boolean policy(int requests, @Nullable Instant last) {
    return DetailsRequestPolicy.requestsAgain(
        new LockedSecurity(StoredCoupon.NONE, requests, last, true, true, true), false, NOW);
  }
}
