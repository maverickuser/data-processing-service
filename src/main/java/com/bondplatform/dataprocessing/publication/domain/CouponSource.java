package com.bondplatform.dataprocessing.publication.domain;

/** Which dataset supplied a security's stored coupon rate (decision 0004). */
public enum CouponSource {
  /** The exchange's daily bhavcopy: the primary source. */
  BSE_BHAVCOPY,
  /** The security details from NSDL: used only while the bhavcopy has supplied none. */
  NSDL
}
