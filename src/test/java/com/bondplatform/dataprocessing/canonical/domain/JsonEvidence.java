package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.util.List;

/** JSON file evidence for tests, read with the real NSDL contract. */
public final class JsonEvidence {

  /** A first attempt for {@code INE831R08076}. */
  public static final JsonCanonicalRun RUN =
      new JsonCanonicalRun(
          JobId.parse("0b6f0a52-6b1e-4d0c-9f43-2f3a5d1c7e10"),
          1,
          Isin.of("INE831R08076"),
          "nsdl-security-json-v1",
          "nsdl-security-mapping-v1");

  /**
   * A coupon rate that is not a number, an entry with one bad date, and ratings of the wrong kind.
   */
  public static final String WITH_ERRORS =
      """
      {"coupensVo": {"couponDetails": {"couponRate": "abc", "couponType": "Simple"}},
       "listingDetails": [
         {"exchangeName": "NSE", "listingDate": "32-13-2026"},
         {"listingDate": "-"},
         {"exchangeName": "BSE", "listingDate": "14-06-2019"}],
       "currentRatings": {}}
      """;

  public static final JsonRejection.ValueIgnored IGNORED =
      new JsonRejection.ValueIgnored(
          "$.instrumentsVo.assetCover.assetCvrPercent",
          new SourceValue.Decimal(new BigDecimal("100")),
          new ValidationIssue(ErrorCode.CONFLICTING_COLLATERAL_DATA, "Coverage with Unsecured."),
          "Ignored supplied coverage.");

  /** Values of the wrong type for text fields, and a listing whose only date is impossible. */
  public static final String TYPED =
      """
      {"issuerName": 89400.00, "issuerTypeOwner": {"a": [1, "x"]},
       "coupensVo": {"couponDetails": {"couponType": true}},
       "listingDetails": [{"listingDate": "31-02-2020"}]}
      """;

  /** The error of a malformed file. */
  public static final ValidationIssue MALFORMED =
      new ValidationIssue(ErrorCode.MALFORMED_JSON, "Line 1, column 2.");

  private JsonEvidence() {}

  /** Returns the evidence of an instrument-details file holding the JSON text. */
  public static JsonFileEvidence read(String json, List<JsonRejection.ValueIgnored> ignored) {
    return new JsonFileEvidence(
        "data-fetch-service-artifacts",
        "nsdl/INE831R08076/run_201/INE831R08076_instrument-details.json",
        NsdlContract.canonical(json),
        ignored);
  }

  /** Returns the evidence of a malformed listings file. */
  public static JsonFileEvidence skipped() {
    return new JsonFileEvidence(
        "data-fetch-service-artifacts",
        "nsdl/INE831R08076/run_201/INE831R08076_listings.json",
        new JsonFileCanonical.Skipped(MALFORMED),
        List.of());
  }
}
