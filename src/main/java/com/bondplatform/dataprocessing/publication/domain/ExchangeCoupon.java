package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.util.Objects;

/**
 * A coupon rate one accepted bhavcopy row reports for its security.
 *
 * @param tradeDate the bhavcopy's trade date, which decides between two bhavcopies' rates
 * @param source the canonical row the rate was mapped from
 */
public record ExchangeCoupon(Isin isin, TradeDate tradeDate, Percent rate, SourceReference source) {

  /** Rejects a missing part. */
  public ExchangeCoupon {
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(tradeDate, "tradeDate");
    Objects.requireNonNull(rate, "rate");
    Objects.requireNonNull(source, "source");
  }
}
