package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code GET /v1/securities/{isin}}, as the read OpenAPI file defines it: every
 * property present, a missing scalar {@code null}, an empty collection {@code []}.
 */
record SecurityResponse(
    String isin,
    @Nullable String issuerName,
    @Nullable String issuerOwnershipType,
    @Nullable String instrumentType,
    @Nullable LocalDate allotmentDate,
    @Nullable LocalDate redemptionDate,
    @Nullable BigDecimal originalFaceValue,
    @Nullable Percent couponRate,
    @Nullable String couponType,
    @Nullable String listingStatus,
    @Nullable String collateralStatus,
    @Nullable String assetCoverageBasis,
    @Nullable Percent assetCoverage,
    List<SecurityView.CollateralAsset> collateralAssets,
    List<Rating> currentRatings,
    List<Rating> ratingHistory,
    List<SecurityView.Listing> listings,
    List<SecurityView.CashFlow> cashFlows,
    Instant createdAt,
    Instant updatedAt) {

  static SecurityResponse of(SecurityView view) {
    SecurityView.Scalars scalars = view.scalars();
    return new SecurityResponse(
        scalars.isin(),
        scalars.issuerName(),
        scalars.issuerOwnershipType(),
        scalars.instrumentType(),
        scalars.allotmentDate(),
        scalars.redemptionDate(),
        scalars.originalFaceValue(),
        Percent.of(scalars.couponRate()),
        scalars.couponType(),
        scalars.listingStatus(),
        scalars.collateralStatus(),
        scalars.assetCoverageBasis(),
        Percent.of(scalars.assetCoverage()),
        view.collateralAssets(),
        view.currentRatings().stream().map(Rating::of).toList(),
        view.ratingHistory().stream().map(Rating::of).toList(),
        view.listings(),
        view.cashFlows(),
        scalars.createdAt(),
        scalars.updatedAt());
  }

  /** A percentage in percentage points: {@code {"value": 8.94, "unit": "PERCENT"}}. */
  record Percent(BigDecimal value, String unit) {

    static @Nullable Percent of(@Nullable BigDecimal value) {
      return value == null ? null : new Percent(value, "PERCENT");
    }
  }

  /** A rating; its source category only decides whether it is current. */
  record Rating(
      @Nullable String ratingAgencyName,
      @Nullable String rating,
      @Nullable String outlook,
      @Nullable String ratingAction,
      @Nullable LocalDate ratingDate,
      @Nullable LocalDate ratingChangeDate,
      @Nullable LocalDate verificationDate,
      Instant firstRecordedAt) {

    static Rating of(RatingObservation observation) {
      return new Rating(
          observation.ratingAgencyName(),
          observation.rating(),
          observation.outlook(),
          observation.ratingAction(),
          observation.ratingDate(),
          observation.ratingChangeDate(),
          observation.verificationDate(),
          observation.firstRecordedAt());
    }
  }
}
