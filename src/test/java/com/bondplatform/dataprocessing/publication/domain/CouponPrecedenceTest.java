package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.publication.domain.CouponPrecedence.BhavcopyCouponChange;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Test cases U-COUPON-01 to U-COUPON-03. */
class CouponPrecedenceTest {

  private static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);
  private static final LocalDate DAY_5 = LocalDate.of(2026, 10, 5);
  private static final LocalDate DAY_9 = LocalDate.of(2026, 10, 9);

  // U-COUPON-01
  @Test
  void bhavcopyReplacesNoRateAnNsdlRateAndAnOlderBhavcopyRate() {
    assertThat(CouponPrecedence.forBhavcopy(StoredCoupon.NONE, coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.REPLACE);
    assertThat(CouponPrecedence.forBhavcopy(nsdl("8.10"), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.REPLACE);
    assertThat(
            CouponPrecedence.forBhavcopy(
                new StoredCoupon(Percent.parse("8.1"), null, null), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.REPLACE);
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("7.5", DAY_2), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.REPLACE);
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("7.5", DAY_5), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.REPLACE);
  }

  // U-COUPON-01
  @Test
  void olderTradeDateNeverReplacesNewerRate() {
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("8.10", DAY_9), coupon("7.5", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.KEEP);
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("8.10", DAY_9), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.KEEP);
  }

  // U-COUPON-01
  @Test
  void sameRateLaterOnlyAdvancesTradeDateAndSameDayChangesNothing() {
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("8.1", DAY_2), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.ADVANCE_TRADE_DATE);
    assertThat(CouponPrecedence.forBhavcopy(bhavcopy("8.1", DAY_5), coupon("8.10", DAY_5)))
        .isEqualTo(BhavcopyCouponChange.KEEP);
  }

  // U-COUPON-02
  @Test
  void securityDetailsSetTheRateOnlyWhileNoBhavcopyHasSupplied() {
    assertThat(CouponPrecedence.securityDetailsMaySet(StoredCoupon.NONE)).isTrue();
    assertThat(CouponPrecedence.securityDetailsMaySet(nsdl("8.10"))).isTrue();
    assertThat(CouponPrecedence.securityDetailsMaySet(bhavcopy("8.10", DAY_5))).isFalse();
  }

  // U-COUPON-03
  @Test
  void ratesDisagreeOnlyWhenBothSourcesHaveNumericallyDifferentRates() {
    Percent nine = Percent.parse("9");

    assertThat(CouponPrecedence.disagrees(nsdl("8.10"), CouponSource.BSE_BHAVCOPY, nine)).isTrue();
    assertThat(CouponPrecedence.disagrees(bhavcopy("8.10", DAY_5), CouponSource.NSDL, nine))
        .isTrue();
    assertThat(
            CouponPrecedence.disagrees(nsdl("9.00"), CouponSource.BSE_BHAVCOPY, Percent.parse("9")))
        .isFalse();
    assertThat(CouponPrecedence.disagrees(bhavcopy("8.10", DAY_5), CouponSource.BSE_BHAVCOPY, nine))
        .isFalse();
    assertThat(CouponPrecedence.disagrees(StoredCoupon.NONE, CouponSource.NSDL, nine)).isFalse();
    assertThat(
            CouponPrecedence.disagrees(
                new StoredCoupon(Percent.parse("8.1"), null, null), CouponSource.NSDL, nine))
        .isFalse();
  }

  private static StoredCoupon nsdl(String rate) {
    return new StoredCoupon(Percent.parse(rate), CouponSource.NSDL, null);
  }

  private static StoredCoupon bhavcopy(String rate, LocalDate tradeDate) {
    return new StoredCoupon(Percent.parse(rate), CouponSource.BSE_BHAVCOPY, tradeDate);
  }

  private static ExchangeCoupon coupon(String rate, LocalDate tradeDate) {
    return new ExchangeCoupon(
        Isin.of("INE001A07AB1"),
        new TradeDate(tradeDate),
        Percent.parse(rate),
        new SourceReference(new JobId(UUID.randomUUID()), "BSE_fgroup05102026.csv", "2"));
  }
}
