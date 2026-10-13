package com.bondplatform.dataprocessing.review.domain;

/**
 * Counts of securities that describe how complete the stored data is right now (LLD section 20.5,
 * decision 0006). Every count includes ISIN-only securities.
 *
 * @param securities every known ISIN
 * @param withoutDetails ISINs with nothing stored but the ISIN: no detail field, coupon rate
 *     included whatever its source, and no cash flow, listing, rating, or collateral asset
 * @param withoutCashFlows ISINs with no cash flow
 * @param withoutCurrentRatings ISINs with no rating recorded as current; earlier ratings do not
 *     count
 * @param withoutCouponRate ISINs with no coupon rate from any source
 * @param withoutCollateralStatus ISINs with no collateral status
 */
public record SecurityMetrics(
    long securities,
    long withoutDetails,
    long withoutCashFlows,
    long withoutCurrentRatings,
    long withoutCouponRate,
    long withoutCollateralStatus) {

  /** Rejects a negative count, or a part larger than the whole. */
  public SecurityMetrics {
    for (long part :
        new long[] {
          withoutDetails,
          withoutCashFlows,
          withoutCurrentRatings,
          withoutCouponRate,
          withoutCollateralStatus
        }) {
      if (part < 0 || part > securities) {
        throw new IllegalArgumentException(
            "Each count must be between 0 and the " + securities + " securities: " + part);
      }
    }
  }
}
