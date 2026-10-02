package com.bondplatform.dataprocessing.contract.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tables a mapping contract may write to and the internal fields each one has (LLD sections 15
 * and 22.1).
 *
 * <p>A mapping contract is checked against this closed set at startup, so it cannot name a table
 * outside the accepted securities data, misspell an internal field, or leave out a constant the
 * table requires.
 */
public final class InternalModel {

  /** The table that holds one row per security, trade date, and exchange. */
  public static final String DAILY_MARKET_SUMMARIES =
      "securities_data.security_daily_market_summaries";

  /** The table that holds one row per security. */
  public static final String SECURITIES = "securities_data.securities";

  private static final Map<String, Target> TARGETS =
      Map.of(
          DAILY_MARKET_SUMMARIES,
          Target.record(
              "isin",
              "securityCode",
              "openPrice",
              "highPrice",
              "lowPrice",
              "closePrice",
              "tradedVolume",
              "numberOfTrades",
              "turnover",
              "faceValue"),
          SECURITIES,
          Target.record(
              "issuerName",
              "issuerOwnershipType",
              "instrumentType",
              "allotmentDate",
              "redemptionDate",
              "originalFaceValue",
              "collateralStatus",
              "assetCoverageBasis",
              "assetCoverage",
              "couponRate",
              "couponType",
              "listingStatus"),
          "securities_data.security_cash_flows",
          Target.collection(
              Map.of(),
              "eventType",
              "recordDate",
              "dueDate",
              "amountPayable",
              "paymentDate",
              "newFaceValue"),
          "securities_data.security_listings",
          Target.collection(Map.of(), "exchangeName", "listingDate"),
          "securities_data.security_ratings",
          Target.collection(
              Map.of("sourceCategory", Set.of("CURRENT", "EARLIER")),
              "ratingAgencyName",
              "rating",
              "outlook",
              "ratingAction",
              "ratingDate",
              "ratingChangeDate",
              "verificationDate"),
          "securities_data.security_collateral_assets",
          Target.collection(Map.of(), "assetType", "collateralDescription", "remarks"));

  private InternalModel() {}

  /** Returns the shape of the named table, or empty if no mapping may write to it. */
  public static Optional<Target> target(String qualifiedTable) {
    return Optional.ofNullable(TARGETS.get(qualifiedTable));
  }

  /**
   * What a mapping may put into one table.
   *
   * @param appendOnly whether the table holds collection entries rather than one record
   * @param fields the internal fields a canonical field may map to
   * @param constants the internal fields that must be given a constant, with their allowed values
   */
  public record Target(boolean appendOnly, Set<String> fields, Map<String, Set<String>> constants) {

    private static Target record(String... fields) {
      return new Target(false, Set.of(fields), Map.of());
    }

    private static Target collection(Map<String, Set<String>> constants, String... fields) {
      return new Target(true, Set.of(fields), constants);
    }
  }
}
