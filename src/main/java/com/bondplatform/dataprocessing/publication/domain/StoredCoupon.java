package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A security's stored coupon rate and where it came from.
 *
 * @param rate the stored rate, or {@code null} when there is none
 * @param source the dataset that supplied it, or {@code null} when there is no rate
 * @param tradeDate the trade date of the bhavcopy that supplied it, or {@code null} unless {@code
 *     source} is {@link CouponSource#BSE_BHAVCOPY}
 */
public record StoredCoupon(
    @Nullable Percent rate, @Nullable CouponSource source, @Nullable LocalDate tradeDate) {

  /** A security with no coupon rate. */
  public static final StoredCoupon NONE = new StoredCoupon(null, null, null);
}
