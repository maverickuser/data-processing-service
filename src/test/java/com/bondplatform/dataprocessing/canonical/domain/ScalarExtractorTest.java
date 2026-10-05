package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ScalarExtractorTest {

  private static final List<String> SCALARS =
      List.of(
          "issuer_name",
          "issuer_ownership_type",
          "instrument_type",
          "allotment_date",
          "redemption_date",
          "original_face_value",
          "collateral_status",
          "asset_coverage_basis",
          "asset_coverage",
          "coupon_rate",
          "coupon_type",
          "listing_status");

  private final ScalarExtractor extractor =
      new ScalarExtractor(NsdlContract.CONTRACT, RuleRegistry.standard());

  // U-JSON-01 (scalars)
  @Test
  void isinDetailsSampleYieldsIssuerScalars() {
    Map<String, @Nullable String> usable = usable(extract("INE831R08076_isin-details.json"));

    assertThat(usable)
        .containsExactlyEntriesOf(
            ordered(
                "issuer_name", "ADITYA BIRLA HOUSING FINANCE LIMITED",
                "issuer_ownership_type", "Non PSU",
                "instrument_type", "Debentures"));
  }

  // U-JSON-01 (scalars), U-JSON-11
  @Test
  void instrumentDetailsSampleYieldsDatesFaceValueAndCollateral() {
    List<JsonCanonicalField> fields = extract("INE831R08076_instrument-details.json");

    assertThat(usable(fields))
        .containsExactlyEntriesOf(
            ordered(
                "allotment_date", "2019-06-10",
                "redemption_date", "2029-06-08",
                "original_face_value", "1000000",
                "collateral_status", "Unsecured"));
    assertThat(field(fields, "asset_coverage").presence()).isEqualTo(FieldPresence.NULL);
    assertThat(field(fields, "asset_coverage").validationStatus())
        .isEqualTo(ValidationStatus.PASSED);
    assertThat(field(fields, "issuer_name").presence()).isEqualTo(FieldPresence.MISSING);
  }

  // U-JSON-01 (scalars)
  @Test
  void couponSamplesYieldRateInPercentagePointsAndType() {
    assertThat(usable(extract("INE831R08076_coupon-details.json")))
        .containsExactlyEntriesOf(ordered("coupon_rate", "8.94", "coupon_type", "Simple"));
    assertThat(usable(extract("INE0O7U07046_coupon-details.json")))
        .containsExactlyEntriesOf(ordered("coupon_rate", "11", "coupon_type", "Simple"));
  }

  // U-JSON-01 (scalars)
  @Test
  void listingsSampleYieldsListingStatusAndRatingsSampleYieldsNoScalar() {
    assertThat(usable(extract("INE831R08076_listings.json")))
        .containsExactlyEntriesOf(ordered("listing_status", "Listed"));
    assertThat(usable(extract("INE831R08076_credit-ratings.json"))).isEmpty();
    assertThat(usable(extract("INE831R08076_redemptions.json"))).isEmpty();
  }

  @Test
  void returnsEverySelectedScalarInContractOrderWithItsPath() {
    List<JsonCanonicalField> fields = extract("INE831R08076_isin-details.json");

    assertThat(fields).extracting(JsonCanonicalField::name).containsExactlyElementsOf(SCALARS);
    assertThat(field(fields, "original_face_value").path())
        .isEqualTo("$.instrumentsVo.instruments.issuePrice");
  }

  // U-JSON-11
  @Test
  void faceValueFromGroupedStringEqualsFaceValueFromNumber() {
    JsonCanonicalField fromString =
        field(
            extractor.extract(
                NsdlContract.parse(
                    "{\"instrumentsVo\": {\"instruments\": {\"issuePrice\": \"10,00,000.00\"}}}")),
            "original_face_value");

    assertThat(fromString.validationStatus()).isEqualTo(ValidationStatus.PASSED);
    assertThat(fromString.normalizedValue()).isEqualTo("1000000.00");
    assertThat(new BigDecimal(Objects.requireNonNull(fromString.parsedValue())))
        .isEqualByComparingTo("1000000");
  }

  @Test
  void invalidScalarIsRejectedAloneAndTheOthersAreKept() {
    List<JsonCanonicalField> fields =
        extractor.extract(
            NsdlContract.parse(
                """
                {"issuerName": 42, "instrumentType": "Bond",
                 "instrumentsVo": {"instruments": {"allotmentDate": "31-02-2019",
                                                   "redemptionDate": "2029-06-08"}}}
                """));

    assertThat(field(fields, "issuer_name").errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_TYPE);
    assertThat(field(fields, "allotment_date").errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_DATE);
    assertThat(usable(fields))
        .containsExactlyEntriesOf(
            ordered("instrument_type", "Bond", "redemption_date", "2029-06-08"));
  }

  // LLD 13.7: a structural error is reported, not treated as an absent field
  @Test
  void scalarBehindValueOfWrongKindIsReportedWithThePath() {
    List<JsonCanonicalField> fields =
        extractor.extract(NsdlContract.parse("{\"coupensVo\": [\"8.94\"], \"issuerName\": \"X\"}"));

    JsonCanonicalField couponRate = field(fields, "coupon_rate");
    assertThat(couponRate.validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(couponRate.errors())
        .containsExactly(
            new ValidationIssue(
                ErrorCode.INVALID_TYPE, "Expected an object at $.coupensVo but found array."));
    assertThat(field(fields, "coupon_type").validationStatus()).isEqualTo(ValidationStatus.FAILED);
    assertThat(usable(fields)).containsExactlyEntriesOf(ordered("issuer_name", "X"));
  }

  private List<JsonCanonicalField> extract(String sample) {
    return extractor.extract(NsdlContract.sample(sample));
  }

  private static JsonCanonicalField field(List<JsonCanonicalField> fields, String name) {
    return fields.stream().filter(field -> field.name().equals(name)).findFirst().orElseThrow();
  }

  /** Returns the usable scalars and their parsed values, in contract order. */
  private static Map<String, @Nullable String> usable(List<JsonCanonicalField> fields) {
    Map<String, @Nullable String> usable = new LinkedHashMap<>();
    fields.stream()
        .filter(JsonCanonicalField::isUsable)
        .forEach(field -> usable.put(field.name(), field.parsedValue()));
    return usable;
  }

  private static Map<String, @Nullable String> ordered(String... namesAndValues) {
    Map<String, @Nullable String> map = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      map.put(namesAndValues[i], namesAndValues[i + 1]);
    }
    return map;
  }
}
