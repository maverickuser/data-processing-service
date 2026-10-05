package com.bondplatform.dataprocessing.contract.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tables a mapping contract may write to and the internal fields each one has (LLD sections 15
 * and 22.1).
 *
 * <p>A mapping contract is checked against this closed set at startup, so it cannot name a table
 * outside the accepted securities data, misspell an internal field, map a value of the wrong type
 * (text into a date column, a decimal into a whole-number column), or leave out a constant the
 * table requires.
 */
public final class InternalModel {

  /** The table that holds one row per security, trade date, and exchange. */
  public static final String DAILY_MARKET_SUMMARIES =
      "securities_data.security_daily_market_summaries";

  /** The table that holds one row per security. */
  public static final String SECURITIES = "securities_data.securities";

  /** The table that holds a security's scheduled payments. */
  public static final String CASH_FLOWS = "securities_data.security_cash_flows";

  /** The table that holds a security's exchange listings. */
  public static final String LISTINGS = "securities_data.security_listings";

  /** The table that holds a security's credit-rating observations. */
  public static final String RATINGS = "securities_data.security_ratings";

  /** The table that holds the assets securing a security. */
  public static final String COLLATERAL_ASSETS = "securities_data.security_collateral_assets";

  private static final Map<String, Target> TARGETS =
      Map.of(
          DAILY_MARKET_SUMMARIES,
          Target.record(
              Map.ofEntries(
                  Map.entry("isin", FieldType.TEXT),
                  Map.entry("securityCode", FieldType.TEXT),
                  Map.entry("openPrice", FieldType.DECIMAL),
                  Map.entry("highPrice", FieldType.DECIMAL),
                  Map.entry("lowPrice", FieldType.DECIMAL),
                  Map.entry("closePrice", FieldType.DECIMAL),
                  Map.entry("tradedVolume", FieldType.INTEGER),
                  Map.entry("numberOfTrades", FieldType.INTEGER),
                  Map.entry("turnover", FieldType.DECIMAL),
                  Map.entry("faceValue", FieldType.DECIMAL))),
          SECURITIES,
          Target.record(
              Map.ofEntries(
                  Map.entry("issuerName", FieldType.TEXT),
                  Map.entry("issuerOwnershipType", FieldType.TEXT),
                  Map.entry("instrumentType", FieldType.TEXT),
                  Map.entry("allotmentDate", FieldType.DATE),
                  Map.entry("redemptionDate", FieldType.DATE),
                  Map.entry("originalFaceValue", FieldType.DECIMAL),
                  Map.entry("collateralStatus", FieldType.TEXT),
                  Map.entry("assetCoverageBasis", FieldType.TEXT),
                  Map.entry("assetCoverage", FieldType.PERCENT),
                  Map.entry("couponRate", FieldType.PERCENT),
                  Map.entry("couponType", FieldType.TEXT),
                  Map.entry("listingStatus", FieldType.TEXT))),
          CASH_FLOWS,
          Target.collection(
              Map.of(),
              Map.of(
                  "eventType", FieldType.TEXT,
                  "recordDate", FieldType.DATE,
                  "dueDate", FieldType.DATE,
                  "amountPayable", FieldType.DECIMAL,
                  "paymentDate", FieldType.DATE,
                  "newFaceValue", FieldType.DECIMAL)),
          LISTINGS,
          Target.collection(
              Map.of(), Map.of("exchangeName", FieldType.TEXT, "listingDate", FieldType.DATE)),
          RATINGS,
          Target.collection(
              Map.of("sourceCategory", Set.of("CURRENT", "EARLIER")),
              Map.of(
                  "ratingAgencyName", FieldType.TEXT,
                  "rating", FieldType.TEXT,
                  "outlook", FieldType.TEXT,
                  "ratingAction", FieldType.TEXT,
                  "ratingDate", FieldType.DATE,
                  "ratingChangeDate", FieldType.DATE,
                  "verificationDate", FieldType.DATE)),
          COLLATERAL_ASSETS,
          Target.collection(
              Map.of(),
              Map.of(
                  "assetType", FieldType.TEXT,
                  "collateralDescription", FieldType.TEXT,
                  "remarks", FieldType.TEXT)));

  private InternalModel() {}

  /** Returns the shape of the named table, or empty if no mapping may write to it. */
  public static Optional<Target> target(String qualifiedTable) {
    return Optional.ofNullable(TARGETS.get(qualifiedTable));
  }

  /**
   * What a mapping may put into one table.
   *
   * @param appendOnly whether the table holds collection entries rather than one record
   * @param fields the internal fields a canonical field may map to, each with the type it stores
   * @param constants the internal fields that must be given a constant, with their allowed values
   */
  public record Target(
      boolean appendOnly, Map<String, FieldType> fields, Map<String, Set<String>> constants) {

    private static Target record(Map<String, FieldType> fields) {
      return new Target(false, fields, Map.of());
    }

    private static Target collection(
        Map<String, Set<String>> constants, Map<String, FieldType> fields) {
      return new Target(true, fields, constants);
    }
  }
}
