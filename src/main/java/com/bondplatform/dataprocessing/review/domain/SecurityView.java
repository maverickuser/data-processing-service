package com.bondplatform.dataprocessing.review.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A security as {@code GET /v1/securities/{isin}} shows it (LLD section 20.1). Field sources,
 * canonical record identifiers and S3 lineage are never part of it.
 *
 * @param scalars the security's own fields
 * @param collateralAssets the assets shown under section 15.2's rule
 * @param currentRatings one per agency, under section 13.6's rule
 * @param ratingHistory every other rating, newest first
 */
public record SecurityView(
    Scalars scalars,
    List<CollateralAsset> collateralAssets,
    List<RatingObservation> currentRatings,
    List<RatingObservation> ratingHistory,
    List<Listing> listings,
    List<CashFlow> cashFlows) {

  /** Copies the lists. */
  public SecurityView {
    collateralAssets = List.copyOf(collateralAssets);
    currentRatings = List.copyOf(currentRatings);
    ratingHistory = List.copyOf(ratingHistory);
    listings = List.copyOf(listings);
    cashFlows = List.copyOf(cashFlows);
  }

  /**
   * The security's own fields; a percentage is {@code null} as a whole when it has no value.
   *
   * @param couponRate the coupon rate in percentage points
   * @param assetCoverage the asset coverage in percentage points
   */
  public record Scalars(
      String isin,
      @Nullable String issuerName,
      @Nullable String issuerOwnershipType,
      @Nullable String instrumentType,
      @Nullable LocalDate allotmentDate,
      @Nullable LocalDate redemptionDate,
      @Nullable BigDecimal originalFaceValue,
      @Nullable BigDecimal couponRate,
      @Nullable String couponType,
      @Nullable String listingStatus,
      @Nullable String collateralStatus,
      @Nullable String assetCoverageBasis,
      @Nullable BigDecimal assetCoverage,
      Instant createdAt,
      Instant updatedAt) {}

  /** A recorded collateral asset. */
  public record CollateralAsset(
      @Nullable String assetType,
      @Nullable String collateralDescription,
      @Nullable String remarks,
      Instant firstRecordedAt) {}

  /** A recorded listing. */
  public record Listing(
      @Nullable String exchangeName, @Nullable LocalDate listingDate, Instant firstRecordedAt) {}

  /** A recorded cash flow. */
  public record CashFlow(
      @Nullable String eventType,
      @Nullable LocalDate recordDate,
      @Nullable LocalDate dueDate,
      @Nullable BigDecimal amountPayable,
      @Nullable LocalDate paymentDate,
      @Nullable BigDecimal newFaceValue,
      Instant firstRecordedAt) {}
}
