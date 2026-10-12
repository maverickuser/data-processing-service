package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.time.LocalDate;

/**
 * Decides which source's coupon rate a security keeps (decision 0004).
 *
 * <p>The bhavcopy is the primary source, and among bhavcopies the latest trade date wins, so a
 * bhavcopy processed late or again never brings back an older rate. NSDL security details are the
 * backup: they set the rate only while no bhavcopy has supplied one.
 */
public final class CouponPrecedence {

  private CouponPrecedence() {}

  /**
   * Decides what a bhavcopy's rate does to the stored one.
   *
   * <p>A rate from a bhavcopy of a later trade date is kept. Otherwise a different rate, or any
   * rate over one from NSDL or none, replaces it; the same rate from a later trade date only
   * advances the stored trade date, which is not a change to the security.
   */
  public static BhavcopyCouponChange forBhavcopy(StoredCoupon stored, ExchangeCoupon incoming) {
    LocalDate storedDate = stored.tradeDate();
    if (stored.source() != CouponSource.BSE_BHAVCOPY || storedDate == null) {
      return BhavcopyCouponChange.REPLACE;
    }
    LocalDate incomingDate = incoming.tradeDate().value();
    if (storedDate.isAfter(incomingDate)) {
      return BhavcopyCouponChange.KEEP;
    }
    Percent storedRate = stored.rate();
    if (storedRate == null || !incoming.rate().equals(storedRate)) {
      return BhavcopyCouponChange.REPLACE;
    }
    return storedDate.isBefore(incomingDate)
        ? BhavcopyCouponChange.ADVANCE_TRADE_DATE
        : BhavcopyCouponChange.KEEP;
  }

  /** What a bhavcopy's rate does to a security's stored coupon. */
  public enum BhavcopyCouponChange {
    /** Nothing is written. */
    KEEP,
    /**
     * Only the stored trade date moves to the bhavcopy's; the rate and the security are as before.
     */
    ADVANCE_TRADE_DATE,
    /** The rate, its source, its trade date, and the security's update time are all written. */
    REPLACE
  }

  /** Returns whether NSDL security details may set the rate: no bhavcopy has supplied one. */
  public static boolean securityDetailsMaySet(StoredCoupon stored) {
    return stored.source() != CouponSource.BSE_BHAVCOPY;
  }

  /**
   * Returns whether a rate from one source disagrees with a stored rate from the other, which is
   * worth a warning: both exist and they differ numerically.
   *
   * @param otherSource the source {@code rate} comes from
   */
  public static boolean disagrees(StoredCoupon stored, CouponSource otherSource, Percent rate) {
    Percent storedRate = stored.rate();
    return storedRate != null
        && stored.source() != null
        && stored.source() != otherSource
        && !storedRate.equals(rate);
  }
}
