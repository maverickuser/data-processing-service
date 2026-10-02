package com.bondplatform.dataprocessing.contract.adapter.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.InvalidContractException;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test case U-CON-02: the loader and validator together, on contracts written as YAML so that each
 * case shows the contract text that is rejected.
 */
class ContractPairValidationTest {

  private static final String NUMBER_RULES =
      "normalize: [trim, normalizeGroupedNumber], validate: [nonNegative]";
  private static final String ISIN_FIELD =
      "isin: { header: ISIN No., type: text, requiredValue: true, normalize: [trim, uppercase] }";
  private static final String OPEN_FIELD =
      "open_price: { header: Open Price, type: decimal, requiredValue: false, "
          + NUMBER_RULES
          + " }";
  private static final String LOW_FIELD =
      "low_price: { header: Low Price, type: decimal, requiredValue: false, " + NUMBER_RULES + " }";

  private static final String CSV_SOURCE =
      """
      id: sample-csv
      version: v1
      dataset: urn:bond-platform:dataset:sample
      format: csv
      file:
        maxBytes: 100
      fields:
        %s
        %s
        %s
      rowRules:
        - { rule: greaterThanOrEqual, left: open_price, right: low_price }
      duplicates:
        key: isin
      """
          .formatted(ISIN_FIELD, OPEN_FIELD, LOW_FIELD);

  private static final String CSV_MAPPING =
      """
      id: sample-mapping
      version: v1
      sourceContract: sample-csv-v1
      target: securities_data.security_daily_market_summaries
      fields:
        isin: isin
        open_price: openPrice
        low_price: lowPrice
      """;

  private static final String JSON_SOURCE =
      """
      id: sample-json
      version: v1
      dataset: urn:bond-platform:dataset:sample-json
      format: json
      file:
        maxCombinedBytes: 100
      scalars:
        issuer_name: { path: $.issuerName, type: text }
        coupon_rate: { path: $.coupensVo.couponDetails.couponRate, type: percent }
      collections:
        ratings:
          path: $.currentRatings[*]
          fields:
            rating: { path: currentRating, type: text }
            rating_date: { path: creditRatingDate, type: date }
      """;

  private static final String JSON_MAPPING =
      """
      id: sample-json-mapping
      version: v1
      sourceContract: sample-json-v1
      security:
        target: securities_data.securities
        fields:
          issuer_name: issuerName
          coupon_rate: couponRate
      collections:
        ratings:
          target: securities_data.security_ratings
          constants: { sourceCategory: CURRENT }
          fields: { rating: rating, rating_date: ratingDate }
      """;

  private final YamlContractLoader loader = new YamlContractLoader();
  private final ContractValidator validator = new ContractValidator(RuleRegistry.standard());

  @Test
  void consistentContractsHaveNoProblems() {
    assertThat(sourceProblems(CSV_SOURCE)).isEmpty();
    assertThat(mappingProblems(CSV_MAPPING, CSV_SOURCE)).isEmpty();
    assertThat(sourceProblems(JSON_SOURCE)).isEmpty();
    assertThat(mappingProblems(JSON_MAPPING, JSON_SOURCE)).isEmpty();
  }

  @Test
  void unknownNormalizerIsReported() {
    assertThat(sourceProblems(isinWith("normalize: [trim, uppercase, runScript]")))
        .containsExactly("field 'isin': No normalizer named 'runScript'");
  }

  @Test
  void unknownValidatorIsReported() {
    String rules = "normalize: [trim, normalizeGroupedNumber], validate: [nonNegative, belowLimit]";

    assertThat(sourceProblems(lowPriceWith(rules)))
        .containsExactly("field 'low_price': No number validator named 'belowLimit'");
  }

  @Test
  void textFieldCannotUseNumberRules() {
    assertThat(sourceProblems(isinWith("normalize: [trim, uppercase], validate: [nonNegative]")))
        .containsExactly("field 'isin' has number validators but is not a number");
    assertThat(sourceProblems(isinWith("normalize: [trim, uppercase, normalizeGroupedNumber]")))
        .containsExactly(
            "field 'isin' is not a number but is normalized with normalizeGroupedNumber");
  }

  @Test
  void numberFieldMustBeTrimmedGroupedAndNonNegative() {
    assertThat(
            sourceProblems(lowPriceWith("normalize: [trim, uppercase], validate: [nonNegative]")))
        .containsExactly(
            "field 'low_price' is a number and must be normalized with"
                + " normalizeGroupedNumber last");
    assertThat(
            sourceProblems(
                lowPriceWith("normalize: [normalizeGroupedNumber, trim], validate: [nonNegative]")))
        .containsExactly(
            "field 'low_price' must be normalized with trim first",
            "field 'low_price' is a number and must be normalized with"
                + " normalizeGroupedNumber last");
    assertThat(sourceProblems(lowPriceWith("normalize: [trim, normalizeGroupedNumber]")))
        .containsExactly("field 'low_price' is a number and must be validated with nonNegative");
    assertThat(
            sourceProblems(
                lowPriceWith(
                    "normalize: [trim, stripTrailingPercent, normalizeGroupedNumber],"
                        + " validate: [nonNegative]")))
        .containsExactly(
            "field 'low_price' is normalized with stripTrailingPercent,"
                + " which CSV does not support");
  }

  @Test
  void everyFieldMustBeTrimmedFirst() {
    assertThat(sourceProblems(isinWith("normalize: [uppercase, trim]")))
        .containsExactly("field 'isin' must be normalized with trim first");
  }

  @Test
  void typeThatCsvDoesNotSupportIsReported() {
    assertThat(sourceProblems(CSV_SOURCE.replace("type: text", "type: date")))
        .contains("field 'isin' has type date, which CSV does not support");
  }

  @Test
  void headersThatDifferOnlyByCaseOrSpacesAreDuplicates() {
    assertThat(sourceProblems(CSV_SOURCE.replace("header: Low Price", "header: ' open price '")))
        .containsExactly("duplicate header 'open price'");
  }

  @Test
  void rowRuleOperandsMustBeTwoDifferentDeclaredNumbers() {
    assertThat(sourceProblems(CSV_SOURCE.replace("right: low_price", "right: floor_price")))
        .containsExactly("row rule operand 'floor_price' is not a declared field");
    assertThat(sourceProblems(CSV_SOURCE.replace("right: low_price", "right: isin")))
        .containsExactly("row rule operand 'isin' is not a number");
    assertThat(sourceProblems(CSV_SOURCE.replace("right: low_price", "right: open_price")))
        .containsExactly("row rule compares 'open_price' with itself");
  }

  @Test
  void duplicateKeyMustBeRequiredUppercasedTextField() {
    assertThat(sourceProblems(CSV_SOURCE.replace("key: isin", "key: security")))
        .containsExactly("duplicates.key 'security' is not a declared field");
    assertThat(sourceProblems(CSV_SOURCE.replace("key: isin", "key: open_price")))
        .containsExactly(
            "duplicates.key 'open_price' must be a text field with requiredValue true");
    assertThat(sourceProblems(CSV_SOURCE.replace("requiredValue: true", "requiredValue: false")))
        .containsExactly("duplicates.key 'isin' must be a text field with requiredValue true");
    assertThat(sourceProblems(isinWith("normalize: [trim]")))
        .containsExactly("duplicates.key 'isin' must be normalized with uppercase");
  }

  @Test
  void sizeLimitsMustBePositive() {
    assertThat(sourceProblems(CSV_SOURCE.replace("maxBytes: 100", "maxBytes: 0")))
        .containsExactly("file.maxBytes must be positive");
    assertThat(sourceProblems(JSON_SOURCE.replace("maxCombinedBytes: 100", "maxCombinedBytes: -1")))
        .containsExactly("file.maxCombinedBytes must be positive");
  }

  @Test
  void jsonPathsMustHaveTheSupportedShapes() {
    assertThat(sourceProblems(JSON_SOURCE.replace("$.issuerName", "issuerName")))
        .containsExactly("scalar 'issuer_name' has an unsupported path 'issuerName'");
    assertThat(sourceProblems(JSON_SOURCE.replace("$.issuerName", "'$.issuers[0].name'")))
        .hasSize(1);
    assertThat(sourceProblems(JSON_SOURCE.replace("$.currentRatings[*]", "$.currentRatings")))
        .containsExactly("collection 'ratings' path must look like $.a.b[*]");
    assertThat(
            sourceProblems(JSON_SOURCE.replace("path: currentRating,", "path: $.currentRating,")))
        .containsExactly(
            "collection 'ratings' field 'rating' has an unsupported path '$.currentRating'");
  }

  @Test
  void typeThatJsonDoesNotSupportIsReported() {
    assertThat(sourceProblems(JSON_SOURCE.replace("type: percent", "type: integer")))
        .containsExactly("scalar 'coupon_rate' has type integer, which JSON does not support");
  }

  @Test
  void collectionWithoutFieldsIsReported() {
    String contract =
        JSON_SOURCE.substring(0, JSON_SOURCE.indexOf("    fields:")) + "    fields: {}\n";

    assertThat(sourceProblems(contract)).containsExactly("collection 'ratings' declares no fields");
  }

  @Test
  void mappingMustNameTheSourceContractItIsPairedWith() {
    assertThat(mappingProblems(CSV_MAPPING.replace("sample-csv-v1", "other-csv-v1"), CSV_SOURCE))
        .containsExactly("sourceContract is 'other-csv-v1' but it is paired with 'sample-csv-v1'");
  }

  @Test
  void mappingMustCoverExactlyTheDeclaredCanonicalFields() {
    assertThat(mappingProblems(CSV_MAPPING + "  close_price: closePrice\n", CSV_SOURCE))
        .containsExactly("fields maps 'close_price', which the source contract does not declare");
    assertThat(mappingProblems(CSV_MAPPING.replace("  low_price: lowPrice\n", ""), CSV_SOURCE))
        .containsExactly("fields does not map 'low_price'");
    assertThat(mappingProblems(CSV_MAPPING.replace("  isin: isin\n", ""), CSV_SOURCE))
        .containsExactly("fields does not map 'isin'");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace("rating_date: ratingDate", "rated_on: ratingDate"),
                JSON_SOURCE))
        .containsExactly(
            "collections.ratings maps 'rated_on', which the source contract does not declare",
            "collections.ratings does not map 'rating_date'");
  }

  @Test
  void internalFieldsMustExistInTheTargetTable() {
    assertThat(mappingProblems(CSV_MAPPING.replace("openPrice", "openPrise"), CSV_SOURCE))
        .containsExactly("fields maps to unknown internal field 'openPrise'");
    assertThat(
            mappingProblems(JSON_MAPPING.replace("rating: rating", "rating: grade"), JSON_SOURCE))
        .containsExactly("collections.ratings maps to unknown internal field 'grade'");
  }

  @Test
  void canonicalTypeMustMatchTheInternalField() {
    assertThat(
            mappingProblems(
                CSV_MAPPING,
                CSV_SOURCE.replace("Open Price, type: decimal", "Open Price, type: integer")))
        .containsExactly(
            "fields maps 'open_price' of type integer to 'openPrice', which holds decimal");
    assertThat(
            mappingProblems(
                CSV_MAPPING
                    .replace("isin: isin", "isin: openPrice")
                    .replace("open_price: openPrice", "open_price: isin"),
                CSV_SOURCE))
        .contains(
            "fields maps 'isin' of type text to 'openPrice', which holds decimal",
            "fields maps 'open_price' of type decimal to 'isin', which holds text");
    assertThat(mappingProblems(JSON_MAPPING, JSON_SOURCE.replace("type: percent", "type: decimal")))
        .containsExactly(
            "fields maps 'coupon_rate' of type decimal to 'couponRate', which holds percent");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace("issuer_name: issuerName", "issuer_name: allotmentDate"),
                JSON_SOURCE))
        .containsExactly(
            "fields maps 'issuer_name' of type text to 'allotmentDate', which holds date");
    assertThat(
            mappingProblems(
                JSON_MAPPING,
                JSON_SOURCE.replace(
                    "creditRatingDate, type: date", "creditRatingDate, type: text")))
        .containsExactly(
            "collections.ratings maps 'rating_date' of type text to 'ratingDate',"
                + " which holds date");
  }

  @Test
  void duplicateKeyMustMapToIsin() {
    String codeField =
        "code: { header: Code, type: text, requiredValue: false, normalize: [trim] }";
    String source = CSV_SOURCE.replace(LOW_FIELD, LOW_FIELD + "\n  " + codeField);
    String mapping = CSV_MAPPING.replace("isin: isin", "isin: securityCode") + "  code: isin\n";

    assertThat(mappingProblems(mapping, source))
        .containsExactly("fields must map 'isin' to 'isin'");
  }

  @Test
  void twoCanonicalFieldsMappedToOneInternalFieldIsReported() {
    assertThat(mappingProblems(CSV_MAPPING.replace("lowPrice", "openPrice"), CSV_SOURCE))
        .containsExactly("duplicate fields internal field 'openPrice'");
  }

  @Test
  void targetsMustBeTheTablesTheInternalModelAllows() {
    assertThat(
            mappingProblems(
                CSV_MAPPING.replace(
                    "securities_data.security_daily_market_summaries", "pg_catalog.pg_authid"),
                CSV_SOURCE))
        .containsExactly(
            "fields target must be securities_data.security_daily_market_summaries"
                + " but is 'pg_catalog.pg_authid'");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace(
                    "securities_data.securities\n", "securities_data.security_listings\n"),
                JSON_SOURCE))
        .containsExactly(
            "fields target must be securities_data.securities"
                + " but is 'securities_data.security_listings'");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace(
                    "securities_data.security_ratings", "securities_data.securities"),
                JSON_SOURCE))
        .containsExactly(
            "collections.ratings target 'securities_data.securities' is not a collection table");
  }

  @Test
  void requiredConstantsMustBePresentWithAllowedValues() {
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace("    constants: { sourceCategory: CURRENT }\n", ""),
                JSON_SOURCE))
        .containsExactly("collections.ratings must set the constant 'sourceCategory'");
    assertThat(mappingProblems(JSON_MAPPING.replace("CURRENT", "LATEST"), JSON_SOURCE))
        .containsExactly(
            "collections.ratings constant 'sourceCategory' has the unsupported value 'LATEST'");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace(
                    "{ sourceCategory: CURRENT }", "{ sourceCategory: CURRENT, region: IN }"),
                JSON_SOURCE))
        .containsExactly("collections.ratings sets the unknown constant 'region'");
  }

  @Test
  void everyDeclaredCollectionMustBeMappedAndNoOther() {
    assertThat(mappingProblems(JSON_MAPPING.replace("  ratings:", "  listings:"), JSON_SOURCE))
        .containsExactly(
            "collection 'listings' is not declared by the source contract",
            "collection 'ratings' is declared by the source contract but not mapped");
    String withoutCollections =
        JSON_MAPPING.substring(0, JSON_MAPPING.indexOf("collections:")) + "collections: {}\n";
    assertThat(mappingProblems(withoutCollections, JSON_SOURCE))
        .containsExactly("collection 'ratings' is declared by the source contract but not mapped");
  }

  @Test
  void twoCollectionsCannotWriteTheSameTargetWithTheSameConstants() {
    String earlierCollection =
        """
          earlier:
            path: $.earlierRatings[*]
            fields:
              rating: { path: currentRating, type: text }
              rating_date: { path: creditRatingDate, type: date }
        """;
    String source = JSON_SOURCE + earlierCollection;
    String earlierMapping =
        """
          earlier:
            target: securities_data.security_ratings
            constants: { sourceCategory: %s }
            fields: { rating: rating, rating_date: ratingDate }
        """;

    assertThat(mappingProblems(JSON_MAPPING + earlierMapping.formatted("CURRENT"), source))
        .containsExactly(
            "duplicate collection target and constants"
                + " 'securities_data.security_ratings {sourceCategory=CURRENT}'");
    assertThat(mappingProblems(JSON_MAPPING + earlierMapping.formatted("EARLIER"), source))
        .isEmpty();
  }

  @Test
  void requireValidListsEveryProblemOfBothContracts() {
    String source = isinWith("normalize: [trim, uppercase, runScript]");
    String mapping = CSV_MAPPING.replace("openPrice", "openPrise");

    assertThatThrownBy(
            () ->
                validator.requireValid(
                    loader.loadSourceContract(source), loader.loadMappingContract(mapping)))
        .isInstanceOfSatisfying(
            InvalidContractException.class,
            exception ->
                assertThat(exception.problems())
                    .containsExactly(
                        "sample-csv-v1: field 'isin': No normalizer named 'runScript'",
                        "sample-mapping-v1: fields maps to unknown internal field 'openPrise'"))
        .hasMessageContaining("sample-csv-v1 with sample-mapping-v1");
  }

  @Test
  void requireValidAcceptsConsistentPair() {
    validator.requireValid(
        loader.loadSourceContract(CSV_SOURCE), loader.loadMappingContract(CSV_MAPPING));
    validator.requireValid(
        loader.loadSourceContract(JSON_SOURCE), loader.loadMappingContract(JSON_MAPPING));
  }

  /** Returns the CSV contract with the isin field's rules replaced. */
  private static String isinWith(String rules) {
    return CSV_SOURCE.replace("normalize: [trim, uppercase] }", rules + " }");
  }

  /** Returns the CSV contract with the low_price field's rules replaced. */
  private static String lowPriceWith(String rules) {
    return CSV_SOURCE.replace(LOW_FIELD, LOW_FIELD.replace(NUMBER_RULES, rules));
  }

  private List<String> sourceProblems(String sourceYaml) {
    return validator.problemsIn(loader.loadSourceContract(sourceYaml));
  }

  private List<String> mappingProblems(String mappingYaml, String sourceYaml) {
    return validator.problemsIn(
        loader.loadMappingContract(mappingYaml), loader.loadSourceContract(sourceYaml));
  }
}
