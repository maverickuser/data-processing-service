package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.bondplatform.dataprocessing.publication.domain.Rating.SourceCategory;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CollectionModelTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final SourceReference SOURCE =
      new SourceReference(
          JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10"),
          "INE831R08076_coupon-details.json",
          "$.coupensVo.cashFlowScheduleDetails.cashFlowSchedule[0]");

  @Test
  void entriesMayHaveEveryValueAbsent() {
    assertThat(new CashFlow(null, null, null, null, null, null).eventType()).isNull();
    assertThat(new Listing(null, null).exchangeName()).isNull();
    assertThat(new CollateralAsset(null, null, null).remarks()).isNull();
    assertThat(
            new Rating(SourceCategory.EARLIER, null, null, null, null, null, null, null)
                .sourceCategory())
        .isEqualTo(SourceCategory.EARLIER);
  }

  @Test
  void ratingNeedsItsSourceCategory() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Rating(missing(), "Agency", "AAA", null, null, null, null, null));
  }

  @Test
  void cashFlowsWithTheSameValuesAreEqual() {
    CashFlow first =
        new CashFlow(
            "Interest", null, LocalDate.of(2027, 6, 8), new BigDecimal("89400"), null, null);
    CashFlow second =
        new CashFlow(
            "Interest", null, LocalDate.of(2027, 6, 8), new BigDecimal("89400"), null, null);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void securityEntryNeedsEveryPart() {
    Listing listing = new Listing("NSE", LocalDate.of(2019, 6, 14));

    assertThat(new SecurityEntry<>(ISIN, listing, SOURCE).value()).isEqualTo(listing);
    assertThatNullPointerException()
        .isThrownBy(() -> new SecurityEntry<>(missing(), listing, SOURCE));
    assertThatNullPointerException()
        .isThrownBy(() -> new SecurityEntry<Listing>(ISIN, missing(), SOURCE));
    assertThatNullPointerException()
        .isThrownBy(() -> new SecurityEntry<>(ISIN, listing, missing()));
  }

  @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
  private static <T> T missing() {
    return null;
  }
}
