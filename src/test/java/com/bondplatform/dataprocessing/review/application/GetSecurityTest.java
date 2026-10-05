package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GetSecurityTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final Instant RECORDED = Instant.parse("2026-09-27T14:31:02Z");
  private static final SecurityView.CollateralAsset ASSET =
      new SecurityView.CollateralAsset("Book Debts", null, null, RECORDED);

  private final SecurityReviewRepository securities = mock(SecurityReviewRepository.class);
  private final GetSecurity getSecurity = new GetSecurity(securities);

  @Test
  void unknownIsinIsEmpty() {
    when(securities.find(ISIN)).thenReturn(Optional.empty());

    assertThat(getSecurity.find(ISIN)).isEmpty();
  }

  // U-REV-05
  @Test
  void securedSecurityShowsEveryRecordedAsset() {
    given("Secured");
    when(securities.collateralAssets(ISIN)).thenReturn(List.of(ASSET));

    assertThat(getSecurity.find(ISIN).orElseThrow().collateralAssets()).containsExactly(ASSET);
  }

  // U-REV-05, owner decision AH-2: Unsecured in any letter case hides every asset
  @Test
  void unsecuredSecurityShowsNoAssetsInAnyCase() {
    for (String status : List.of("Unsecured", "UNSECURED")) {
      given(status);

      assertThat(getSecurity.find(ISIN).orElseThrow().collateralAssets()).isEmpty();
    }
    verify(securities, never()).collateralAssets(ISIN);
  }

  @Test
  void securityWithoutStatusShowsItsAssets() {
    given(null);
    when(securities.collateralAssets(ISIN)).thenReturn(List.of(ASSET));

    assertThat(getSecurity.find(ISIN).orElseThrow().collateralAssets()).containsExactly(ASSET);
  }

  @Test
  void ratingsAreSplitAndCollectionsPassedThrough() {
    given("Secured");
    RatingObservation current =
        new RatingObservation(
            true, "ICRA", "AA", null, null, LocalDate.of(2020, 1, 1), null, null, RECORDED);
    RatingObservation earlier =
        new RatingObservation(
            false, "ICRA", "A", null, null, LocalDate.of(2019, 1, 1), null, null, RECORDED);
    SecurityView.Listing listing =
        new SecurityView.Listing("NSE", LocalDate.of(2019, 6, 14), RECORDED);
    SecurityView.CashFlow cashFlow =
        new SecurityView.CashFlow("Interest", null, null, null, null, null, RECORDED);
    when(securities.ratings(ISIN)).thenReturn(List.of(earlier, current));
    when(securities.listings(ISIN)).thenReturn(List.of(listing));
    when(securities.cashFlows(ISIN)).thenReturn(List.of(cashFlow));

    SecurityView view = getSecurity.find(ISIN).orElseThrow();

    assertThat(view.currentRatings()).containsExactly(current);
    assertThat(view.ratingHistory()).containsExactly(earlier);
    assertThat(view.listings()).containsExactly(listing);
    assertThat(view.cashFlows()).containsExactly(cashFlow);
    assertThat(view.scalars().isin()).isEqualTo(ISIN.value());
  }

  private void given(@Nullable String collateralStatus) {
    when(securities.find(ISIN))
        .thenReturn(
            Optional.of(
                new SecurityView.Scalars(
                    ISIN.value(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    collateralStatus,
                    null,
                    null,
                    RECORDED,
                    RECORDED)));
  }
}
