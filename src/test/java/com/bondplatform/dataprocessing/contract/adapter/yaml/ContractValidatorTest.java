package com.bondplatform.dataprocessing.contract.adapter.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.ContractValidator;
import com.bondplatform.dataprocessing.contract.domain.InvalidContractException;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test case U-CON-02. Contracts are written as YAML and read through the loader, so each case shows
 * the contract text that is rejected.
 */
class ContractValidatorTest {

  private static final String CSV_SOURCE =
      """
      id: sample-csv
      version: v1
      dataset: urn:bond-platform:dataset:sample
      format: csv
      file:
        maxBytes: 100
      fields:
        isin: { header: ISIN No., type: text, requiredValue: true, normalize: [trim, uppercase] }
        open_price: { header: Open Price, type: decimal, requiredValue: false, normalize: [trim, normalizeGroupedNumber], validate: [nonNegative] }
        low_price: { header: Low Price, type: decimal, requiredValue: false, normalize: [trim] }
      rowRules:
        - { rule: greaterThanOrEqual, left: open_price, right: low_price }
      duplicates:
        key: isin
      """;

  private static final String CSV_MAPPING =
      """
      id: sample-mapping
      version: v1
      sourceContract: sample-csv-v1
      target: securities_data.security_daily_market_summaries
      fields:
        isin: isin
        open_price: openPrice
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
        listings:
          path: $.listingDetails[*]
          fields:
            exchange_name: { path: exchangeName, type: text }
            listing_date: { path: listingDate, type: date }
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
        listings:
          target: securities_data.security_listings
          constants: { sourceCategory: CURRENT }
          fields: { exchange_name: exchangeName, listing_date: listingDate }
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
    assertThat(sourceProblems(CSV_SOURCE.replace("[trim, uppercase]", "[trim, runScript]")))
        .singleElement()
        .asString()
        .contains("field 'isin'", "UNKNOWN_RULE", "runScript");
  }

  @Test
  void unknownValidatorIsReported() {
    assertThat(sourceProblems(CSV_SOURCE.replace("[nonNegative]", "[belowLimit]")))
        .singleElement()
        .asString()
        .contains("field 'open_price'", "belowLimit");
  }

  @Test
  void numberValidatorOnTextFieldIsReported() {
    String contract =
        CSV_SOURCE.replace(
            "normalize: [trim, uppercase] }", "normalize: [trim], validate: [nonNegative] }");

    assertThat(sourceProblems(contract))
        .containsExactly("field 'isin' has number validators but is not a number");
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
  void rowRuleOperandsMustBeDeclaredNumbers() {
    assertThat(sourceProblems(CSV_SOURCE.replace("right: low_price", "right: floor_price")))
        .containsExactly("row rule operand 'floor_price' is not a declared field");
    assertThat(sourceProblems(CSV_SOURCE.replace("right: low_price", "right: isin")))
        .containsExactly("row rule operand 'isin' is not a number");
  }

  @Test
  void duplicateKeyMustBeDeclared() {
    assertThat(sourceProblems(CSV_SOURCE.replace("key: isin", "key: security")))
        .containsExactly("duplicates.key 'security' is not a declared field");
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
    assertThat(sourceProblems(JSON_SOURCE.replace("$.listingDetails[*]", "$.listingDetails")))
        .containsExactly("collection 'listings' path must look like $.a.b[*]");
    assertThat(sourceProblems(JSON_SOURCE.replace("path: exchangeName", "path: $.exchangeName")))
        .containsExactly(
            "collection 'listings' field 'exchange_name' has an unsupported path '$.exchangeName'");
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

    assertThat(sourceProblems(contract))
        .containsExactly("collection 'listings' declares no fields");
  }

  @Test
  void mappingMustNameTheSourceContractItIsPairedWith() {
    assertThat(mappingProblems(CSV_MAPPING.replace("sample-csv-v1", "other-csv-v1"), CSV_SOURCE))
        .containsExactly("sourceContract is 'other-csv-v1' but it is paired with 'sample-csv-v1'");
  }

  @Test
  void mappingOfUndeclaredCanonicalFieldIsReported() {
    assertThat(mappingProblems(CSV_MAPPING + "  close_price: closePrice\n", CSV_SOURCE))
        .containsExactly("fields maps 'close_price', which the source contract does not declare");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace("listing_date: listingDate", "listed_on: listingDate"),
                JSON_SOURCE))
        .containsExactly(
            "collections.listings maps 'listed_on', which the source contract does not declare");
  }

  @Test
  void twoCanonicalFieldsMappedToOneInternalFieldIsReported() {
    assertThat(mappingProblems(CSV_MAPPING.replace("openPrice", "isin"), CSV_SOURCE))
        .containsExactly("duplicate fields internal field 'isin'");
    assertThat(
            mappingProblems(
                JSON_MAPPING.replace(
                    "exchange_name: exchangeName", "exchange_name: sourceCategory"),
                JSON_SOURCE))
        .containsExactly("duplicate collections.listings internal field 'sourceCategory'");
  }

  @Test
  void mappingOfUndeclaredCollectionIsReported() {
    assertThat(mappingProblems(JSON_MAPPING.replace("  listings:", "  ratings:"), JSON_SOURCE))
        .containsExactly("collection 'ratings' is not declared by the source contract");
    assertThat(
            mappingProblems(
                CSV_MAPPING.replace(
                    "id: sample-mapping",
                    "id: sample-mapping\nsecurity: { target: a.b, fields: {} }\n"
                        + "collections: { x: { target: a.b } }"),
                CSV_SOURCE))
        .containsExactly("collection 'x' is not declared by the source contract");
  }

  @Test
  void targetMustBeSchemaQualified() {
    assertThat(
            mappingProblems(
                CSV_MAPPING.replace("securities_data.security_daily_market_summaries", "Summaries"),
                CSV_SOURCE))
        .containsExactly("fields target 'Summaries' must be schema.table in snake_case");
  }

  @Test
  void requireValidNamesTheContractAndListsEveryProblem() {
    SourceContract source =
        loader.loadSourceContract(
            CSV_SOURCE.replace("[nonNegative]", "[belowLimit]").replace("key: isin", "key: nope"));
    MappingContract mapping = loader.loadMappingContract(CSV_MAPPING);

    assertThatThrownBy(() -> validator.requireValid(source, mapping))
        .isInstanceOfSatisfying(
            InvalidContractException.class,
            exception -> assertThat(exception.problems()).hasSize(2))
        .hasMessageContaining("sample-csv-v1")
        .hasMessageContaining("belowLimit")
        .hasMessageContaining("nope");
  }

  @Test
  void requireValidChecksTheMappingOnceTheSourceIsSound() {
    SourceContract source = loader.loadSourceContract(CSV_SOURCE);
    MappingContract mapping = loader.loadMappingContract(CSV_MAPPING + "  turnover: turnover\n");

    assertThatThrownBy(() -> validator.requireValid(source, mapping))
        .isInstanceOf(InvalidContractException.class)
        .hasMessageContaining("sample-mapping-v1")
        .hasMessageContaining("turnover");
    validator.requireValid(source, loader.loadMappingContract(CSV_MAPPING));
  }

  @Test
  void duplicateFieldNameIsRejectedWhenTheFileIsRead() {
    String contract = CSV_SOURCE.replace("  low_price:", "  open_price:");

    assertThatThrownBy(() -> loader.loadSourceContract(contract))
        .hasMessageContaining("not valid YAML")
        .hasMessageContaining("open_price");
  }

  private List<String> sourceProblems(String sourceYaml) {
    return validator.problemsIn(loader.loadSourceContract(sourceYaml));
  }

  private List<String> mappingProblems(String mappingYaml, String sourceYaml) {
    return validator.problemsIn(
        loader.loadMappingContract(mappingYaml), loader.loadSourceContract(sourceYaml));
  }
}
