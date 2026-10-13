package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Test case U-DETAILS-01. */
class DetailsRequestPolicyTest {

  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

  @Test
  void securityWithoutCouponIsAskedAgainUnderLimitAndDayAfterLastRequest() {
    assertThat(policy(0, null, false)).isTrue();
    assertThat(policy(1, NOW.minus(Duration.ofDays(1)), false)).isTrue();
    assertThat(policy(2, NOW.minus(Duration.ofDays(5)), false)).isTrue();
  }

  @Test
  void securityIsNotAskedAgainTooSoonOrBeyondTheLimit() {
    assertThat(policy(1, NOW.minus(Duration.ofDays(1)).plusSeconds(1), false)).isFalse();
    assertThat(policy(3, NOW.minus(Duration.ofDays(30)), false)).isFalse();
  }

  @Test
  void securityWithCouponStoredOrInFileIsNotAskedAgain() {
    LockedSecurity withCoupon =
        new LockedSecurity(new StoredCoupon(Percent.parse("8"), CouponSource.NSDL, null), 1, null);

    assertThat(DetailsRequestPolicy.requestsAgain(withCoupon, false, NOW)).isFalse();
    assertThat(policy(1, null, true)).isFalse();
  }

  @Test
  void negativeRequestCountIsRefused() {
    assertThatThrownBy(() -> new LockedSecurity(StoredCoupon.NONE, -1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static boolean policy(int requests, @Nullable Instant last, boolean couponInFile) {
    return DetailsRequestPolicy.requestsAgain(
        new LockedSecurity(StoredCoupon.NONE, requests, last), couponInFile, NOW);
  }
}
