package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCanonicalizerTest {

  private static final Isin ISIN = Isin.of("INE831R08076");

  private final JsonCanonicalizer canonicalizer =
      new JsonCanonicalizer(NsdlContract.CONTRACT, RuleRegistry.standard());

  // U-JSON-01, U-JSON-02: every path is applied to every file, whatever its name
  @ParameterizedTest
  @ValueSource(
      strings = {
        "INE831R08076_isin-details.json",
        "INE831R08076_instrument-details.json",
        "INE831R08076_coupon-details.json",
        "INE831R08076_credit-ratings.json",
        "INE831R08076_listings.json"
      })
  void everySampleIsReadWithoutErrorsOrWarnings(String fileName) {
    JsonFileCanonical.Read read = read(NsdlContract.sample(fileName));

    assertThat(read.scalars()).hasSize(NsdlContract.CONTRACT.scalars().size());
    assertThat(read.structureIssues()).isEmpty();
    assertThat(read.warnings()).isEmpty();
    assertThat(read.scalars()).allMatch(field -> field.errors().isEmpty());
    assertThat(read.entries()).allMatch(entry -> entry.disposition() == EntryDisposition.ACCEPTED);
    assertThat(read.hasUsableData()).isTrue();
  }

  // U-JSON-02
  @Test
  void fileWithoutSelectedPathsHasNoUsableDataAndNoError() {
    JsonFileCanonical.Read read = read(NsdlContract.sample("INE831R08076_redemptions.json"));

    assertThat(read.hasUsableData()).isFalse();
    assertThat(read.entries()).isEmpty();
    assertThat(read.structureIssues()).isEmpty();
    assertThat(read.scalars()).allMatch(field -> field.errors().isEmpty());
  }

  @Test
  void onlyAcceptedEntriesOrValidScalarsAreUsable() {
    assertThat(
            read(NsdlContract.parse("{\"listingDetails\": [{\"exchangeName\": \"-\"}]}"))
                .hasUsableData())
        .isFalse();
    assertThat(
            read(NsdlContract.parse("{\"listingDetails\": [{\"exchangeName\": \"BSE\"}]}"))
                .hasUsableData())
        .isTrue();
  }

  // U-JSON-07
  @Test
  void malformedFileIsSkippedWithOneError() {
    assertThat(
            canonicalizer.canonicalize(
                ISIN,
                new JsonRead.Malformed("The file is not well-formed JSON at line 1, column 7")))
        .isEqualTo(
            new JsonFileCanonical.Skipped(
                new ValidationIssue(
                    ErrorCode.MALFORMED_JSON,
                    "The file is not well-formed JSON at line 1, column 7.")));
  }

  // U-JSON-06
  @Test
  void payloadIsinThatDiffersIsOnlyWarned() {
    JsonFileCanonical.Read read =
        read(NsdlContract.parse("{\"isin\": \" ine999a01011 \", \"issuerName\": \"Acme\"}"));

    assertThat(read.warnings())
        .containsExactly(
            "The payload ISIN INE999A01011 differs from the file name's ISIN INE831R08076;"
                + " the file name's is used.");
    assertThat(read.structureIssues()).isEmpty();
    assertThat(read.scalars()).allMatch(field -> field.errors().isEmpty());
    assertThat(read.hasUsableData()).isTrue();
  }

  // U-JSON-06
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"isin\": \"ine831r08076 \"}",
        "{\"isin\": null}",
        "{\"isin\": \"-\"}",
        "{\"isin\": 42}",
        "{}",
        "[]"
      })
  void matchingOrUnusablePayloadIsinGivesNoWarning(String json) {
    assertThat(read(NsdlContract.parse(json)).warnings()).isEmpty();
  }

  @Test
  void structureIssuesFromScalarsAndCollectionsAreCombined() {
    JsonFileCanonical.Read read =
        read(NsdlContract.parse("{\"coupensVo\": \"x\", \"listingDetails\": 3}"));

    assertThat(read.structureIssues())
        .extracting(StructureIssue::path)
        .containsExactly("$.coupensVo", "$.listingDetails");
  }

  @Test
  void rootThatIsNotAnObjectIsOneStructureIssue() {
    JsonFileCanonical.Read read = read(NsdlContract.parse("[1, 2]"));

    assertThat(read.structureIssues())
        .containsExactly(
            new StructureIssue(
                "$",
                new ValidationIssue(
                    ErrorCode.INVALID_TYPE, "Expected an object at $ but found array.")));
    assertThat(read.hasUsableData()).isFalse();
    assertThat(read.entries()).isEqualTo(List.of());
  }

  private JsonFileCanonical.Read read(JsonValue root) {
    return (JsonFileCanonical.Read) canonicalizer.canonicalize(ISIN, new JsonRead.Parsed(root));
  }
}
