package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.publication.domain.ExchangeCoupon;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Test case U-MAP-05. */
class ExchangeCouponMapperTest {

  private static final ExchangeCouponMapper MAPPER =
      new ExchangeCouponMapper(BhavcopyContract.MAPPING);

  @Test
  void acceptedRowGivesItsRateWithTheRunsTradeDateAndItsSource() {
    ExchangeCoupon coupon = MAPPER.map(GoldenBhavcopy.RUN, golden(2)).orElseThrow();

    assertThat(coupon.isin()).isEqualTo(Isin.of("INE001A07AB1"));
    assertThat(coupon.tradeDate()).isEqualTo(new TradeDate(LocalDate.of(2026, 1, 1)));
    assertThat(coupon.rate()).isEqualTo(Percent.parse("8.5"));
    assertThat(coupon.rate().value().toPlainString()).isEqualTo("8.50");
    assertThat(coupon.source())
        .isEqualTo(new SourceReference(GoldenBhavcopy.RUN.jobId(), "BSE_fgroup01012026.csv", "2"));
  }

  @Test
  void quarantinedRowsZeroAndBlankRatesGiveNone() {
    assertThat(MAPPER.map(GoldenBhavcopy.RUN, golden(3))).isEmpty();
    assertThat(MAPPER.map(GoldenBhavcopy.RUN, golden(9))).isEmpty();
    assertThat(MAPPER.map(GoldenBhavcopy.RUN, golden(5))).isEmpty();
  }

  @Test
  void ignoredInvalidRateGivesNone() {
    assertThat(MAPPER.map(GoldenBhavcopy.RUN, GoldenBhavcopy.acceptedWithIgnoredCoupon()))
        .isEmpty();
  }

  @Test
  void contractWithoutSecurityFieldsGivesNoRates() {
    ExchangeCouponMapper mapper = new ExchangeCouponMapper(contract(Map.of()));

    assertThat(mapper.map(GoldenBhavcopy.RUN, golden(2))).isEmpty();
  }

  @Test
  void contractFillingAnotherSecurityFieldOrNotMappingTheIsinIsRefused() {
    assertThatThrownBy(
            () -> new ExchangeCouponMapper(contract(Map.of("coupon_rate", "couponType"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Contract bse-debt-bhavcopy-mapping-v2 fills security field couponType");

    MappingContract withoutIsin =
        new MappingContract(
            BhavcopyContract.MAPPING.id(),
            BhavcopyContract.MAPPING.sourceContract(),
            new MappingContract.RecordMapping(
                BhavcopyContract.MAPPING.primary().target(), Map.of("face_value", "faceValue")),
            List.of(),
            Map.of("coupon_rate", "couponRate"));
    assertThatThrownBy(() -> new ExchangeCouponMapper(withoutIsin))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Contract bse-debt-bhavcopy-mapping-v2 does not map the ISIN");
  }

  private static MappingContract contract(Map<String, String> securityFields) {
    return new MappingContract(
        BhavcopyContract.MAPPING.id(),
        BhavcopyContract.MAPPING.sourceContract(),
        BhavcopyContract.MAPPING.primary(),
        List.of(),
        securityFields);
  }

  private static CanonicalRow golden(long recordNumber) {
    return GoldenBhavcopy.rows().stream()
        .filter(row -> row.record().recordNumber() == recordNumber)
        .findFirst()
        .orElseThrow();
  }
}
