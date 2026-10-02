package com.bondplatform.dataprocessing.contract.adapter.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.ContractFormatException;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.Comparison;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.RowRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Test case U-CON-01, and the shape checks of the loader. */
class YamlContractLoaderTest {

  private static final String MINIMAL_CSV =
      """
      id: sample-csv
      version: v1
      dataset: urn:bond-platform:dataset:sample
      format: csv
      file:
        maxBytes: 100
      fields:
        isin: { header: ISIN No., type: text, requiredValue: true, normalize: [trim] }
      duplicates:
        key: isin
      """;

  private final YamlContractLoader loader = new YamlContractLoader();

  @Test
  void loadsTheCommittedBseSourceContract() {
    SourceContract.Csv contract =
        (SourceContract.Csv) loader.loadSourceContract(committed("bse-debt-bhavcopy-csv-v1"));

    assertThat(contract.id().name()).isEqualTo("bse-debt-bhavcopy-csv-v1");
    assertThat(contract.dataset().value()).isEqualTo("urn:bond-platform:dataset:bse-debt-trades");
    assertThat(contract.maxBytes()).isEqualTo(10_485_760L);
    assertThat(contract.duplicateKey()).isEqualTo("isin");
    assertThat(contract.fields())
        .extracting(CsvField::name)
        .containsExactly(
            "security_code",
            "open_price",
            "high_price",
            "low_price",
            "close_price",
            "traded_volume",
            "number_of_trades",
            "turnover",
            "face_value",
            "isin");
    assertThat(contract.fields())
        .filteredOn(CsvField::requiredValue)
        .extracting(CsvField::name)
        .containsExactly("isin");
    assertThat(field(contract, "close_price"))
        .isEqualTo(
            new CsvField(
                "close_price",
                "Close Price",
                FieldType.DECIMAL,
                false,
                List.of("trim", "blankToNull", "normalizeGroupedNumber"),
                List.of("nonNegative")));
    assertThat(field(contract, "traded_volume").type()).isEqualTo(FieldType.INTEGER);
    assertThat(field(contract, "isin").normalize())
        .containsExactly("trim", "blankToNull", "uppercase");
    assertThat(contract.rowRules())
        .hasSize(5)
        .contains(new RowRule(Comparison.GREATER_THAN_OR_EQUAL, "high_price", "low_price"))
        .contains(new RowRule(Comparison.LESS_THAN_OR_EQUAL, "close_price", "high_price"));
  }

  @Test
  void loadsTheCommittedNsdlSourceContract() {
    SourceContract.Json contract =
        (SourceContract.Json) loader.loadSourceContract(committed("nsdl-security-json-v1"));

    assertThat(contract.id().name()).isEqualTo("nsdl-security-json-v1");
    assertThat(contract.dataset().value()).isEqualTo("urn:bond-platform:dataset:nsdl-security");
    assertThat(contract.maxCombinedBytes()).isEqualTo(10_485_760L);
    assertThat(contract.scalars())
        .hasSize(12)
        .contains(new JsonField("issuer_name", "$.issuerName", FieldType.TEXT))
        .contains(
            new JsonField(
                "original_face_value", "$.instrumentsVo.instruments.issuePrice", FieldType.DECIMAL))
        .contains(
            new JsonField("coupon_rate", "$.coupensVo.couponDetails.couponRate", FieldType.PERCENT))
        .contains(
            new JsonField(
                "allotment_date", "$.instrumentsVo.instruments.allotmentDate", FieldType.DATE));
    assertThat(contract.collections())
        .extracting(JsonCollection::name)
        .containsExactly(
            "cash_flows", "listings", "current_ratings", "earlier_ratings", "collateral_assets");
    JsonCollection cashFlows = contract.collections().get(0);
    assertThat(cashFlows.path())
        .isEqualTo("$.coupensVo.cashFlowScheduleDetails.cashFlowSchedule[*]");
    assertThat(cashFlows.fields())
        .contains(new JsonField("event_type", "cashFlowsEvent", FieldType.TEXT))
        .contains(new JsonField("new_face_value", "newFaceVal", FieldType.DECIMAL));
  }

  @Test
  void earlierRatingsReuseTheCurrentRatingFields() {
    SourceContract.Json contract =
        (SourceContract.Json) loader.loadSourceContract(committed("nsdl-security-json-v1"));

    assertThat(contract.collections().get(3).fields())
        .hasSize(7)
        .isEqualTo(contract.collections().get(2).fields());
  }

  @Test
  void loadsTheCommittedBseMappingContract() {
    MappingContract contract =
        loader.loadMappingContract(committed("bse-debt-bhavcopy-mapping-v1"));

    assertThat(contract.id().name()).isEqualTo("bse-debt-bhavcopy-mapping-v1");
    assertThat(contract.sourceContract()).isEqualTo("bse-debt-bhavcopy-csv-v1");
    assertThat(contract.primary().target())
        .isEqualTo("securities_data.security_daily_market_summaries");
    assertThat(contract.primary().fields())
        .hasSize(10)
        .containsEntry("face_value", "faceValue")
        .containsEntry("number_of_trades", "numberOfTrades");
    assertThat(contract.collections()).isEmpty();
  }

  @Test
  void loadsTheCommittedNsdlMappingContract() {
    MappingContract contract = loader.loadMappingContract(committed("nsdl-security-mapping-v1"));

    assertThat(contract.sourceContract()).isEqualTo("nsdl-security-json-v1");
    assertThat(contract.primary().target()).isEqualTo("securities_data.securities");
    assertThat(contract.primary().fields())
        .hasSize(12)
        .containsEntry("issuer_ownership_type", "issuerOwnershipType")
        .containsEntry("original_face_value", "originalFaceValue");
    assertThat(contract.collections())
        .extracting(CollectionMapping::name)
        .containsExactly(
            "cash_flows", "listings", "current_ratings", "earlier_ratings", "collateral_assets");
    CollectionMapping earlierRatings = contract.collections().get(3);
    assertThat(earlierRatings.target()).isEqualTo("securities_data.security_ratings");
    assertThat(earlierRatings.constants()).containsEntry("sourceCategory", "EARLIER");
    assertThat(earlierRatings.fields()).containsEntry("rating_agency_name", "ratingAgencyName");
    assertThat(contract.collections().get(0).fields())
        .containsEntry("new_face_value", "newFaceValue");
    assertThat(contract.collections().get(1).constants()).isEmpty();
  }

  @Test
  void minimalContractLoads() {
    SourceContract.Csv contract = (SourceContract.Csv) loader.loadSourceContract(MINIMAL_CSV);

    assertThat(contract.rowRules()).isEmpty();
    assertThat(contract.fields().get(0).validate()).isEmpty();
  }

  @Test
  void reportsMissingKeyWithItsLocation() {
    assertRejected(MINIMAL_CSV.replace("file:\n  maxBytes: 100\n", "file: {}\n"), "file.maxBytes");
    assertRejected(MINIMAL_CSV.replace("version: v1\n", ""), "version");
    assertRejected(MINIMAL_CSV.replace("header: ISIN No., ", ""), "fields.isin.header");
  }

  @Test
  void reportsWrongValueKindWithItsLocation() {
    assertRejected(MINIMAL_CSV.replace("maxBytes: 100", "maxBytes: lots"), "file.maxBytes");
    assertRejected(
        MINIMAL_CSV.replace("requiredValue: true", "requiredValue: yes please"),
        "fields.isin.requiredValue");
    assertRejected(
        MINIMAL_CSV.replace("normalize: [trim]", "normalize: trim"), "fields.isin.normalize");
    assertRejected(
        MINIMAL_CSV.replace("normalize: [trim]", "normalize: [1, 2]"), "fields.isin.normalize");
    assertRejected(MINIMAL_CSV.replace("version: v1", "version: 1"), "version");
    assertRejected(MINIMAL_CSV.replace("  key: isin", "  key: ''"), "duplicates.key");
    assertRejected(MINIMAL_CSV.replace("  maxBytes: 100", "  - 100"), "file");
    assertRejected(MINIMAL_CSV + "rowRules: oops\n", "rowRules");
  }

  @Test
  void reportsUnknownNamesWithTheirLocation() {
    assertRejected(MINIMAL_CSV.replace("type: text", "type: money"), "fields.isin.type");
    assertRejected(MINIMAL_CSV.replace("format: csv", "format: xml"), "format");
    assertRejected(MINIMAL_CSV.replace("urn:bond-platform:dataset:sample", "sample"), "dataset");
    assertRejected(
        MINIMAL_CSV + "rowRules:\n  - { rule: equals, left: isin, right: isin }\n",
        "rowRules[0].rule");
  }

  @Test
  void misspelledOrUnsupportedKeyIsRejectedWithItsLocation() {
    assertRejected(
        MINIMAL_CSV.replace("normalize: [trim]", "normalise: [trim]"), "fields.isin.normalise");
    assertRejected(MINIMAL_CSV + "numericFormat:\n  decimalSeparator: ','\n", "numericFormat");
    assertRejected(
        MINIMAL_CSV.replace("  maxBytes: 100", "  maxBytes: 100\n  dialect: excel-tab"),
        "file.dialect");
    assertRejected(
        MINIMAL_CSV.replace("  key: isin", "  key: isin\n  winner: firstRow"), "duplicates.winner");
    assertRejected(
        MINIMAL_CSV + "rowRules:\n  - { rule: lessThanOrEqual, left: isin, rihgt: isin }\n",
        "rowRules[0].rihgt");
  }

  @Test
  void unsupportedKeyInJsonContractIsRejected() {
    String json = committed("nsdl-security-json-v1");

    assertRejected(json + "types:\n  decimal: { normalize: [evaluateExpression] }\n", "types");
    assertRejected(
        json.replace(
            "{ path: $.issuerName, type: text }",
            "{ path: $.issuerName, type: text, validate: [bogus] }"),
        "scalars.issuer_name.validate");
    assertRejected(
        json.replace(
            "  maxCombinedBytes: 10485760", "  maxCombinedBytes: 10485760\n  onMalformed: ignore"),
        "file.onMalformed");
    assertRejected(
        json.replace(
            "    path: $.listingDetails[*]", "    path: $.listingDetails[*]\n    dedupe: false"),
        "collections.listings.dedupe");
  }

  @Test
  void unsupportedKeyInMappingContractIsRejected() {
    String csvMapping = committed("bse-debt-bhavcopy-mapping-v1");
    String jsonMapping = committed("nsdl-security-mapping-v1");

    assertMappingRejected(csvMapping + "eligibleDisposition: FAILED\n", "eligibleDisposition");
    assertMappingRejected(jsonMapping + "rules:\n  - { rule: bogusRule }\n", "rules");
    assertMappingRejected(
        jsonMapping.replace(
            "  target: securities_data.securities",
            "  target: securities_data.securities\n  key: { isin: identity.isin }"),
        "security.key");
    assertMappingRejected(
        jsonMapping.replace(
            "    constants: { sourceCategory: CURRENT }",
            "    constants: { sourceCategory: CURRENT }\n    onDuplicate: overwrite"),
        "collections.current_ratings.onDuplicate");
  }

  @Test
  void mappingWithBothShapesIsRejected() {
    String both =
        committed("bse-debt-bhavcopy-mapping-v1")
            + "security:\n  target: securities_data.securities\n  fields: { isin: isin }\n"
            + "collections: {}\n";

    assertMappingRejected(both, "target");
  }

  @Test
  void keysMustBeText() {
    assertRejected(MINIMAL_CSV.replace("  isin: {", "  123: {"), "fields");
    assertRejected(MINIMAL_CSV.replace("  isin: {", "  yes: {"), "fields");
    assertRejected(MINIMAL_CSV.replace("  isin: {", "  ~: {"), "fields");
  }

  @Test
  void duplicateKeyIsRejectedRatherThanOverridden() {
    String contract =
        MINIMAL_CSV.replace(
            "duplicates:",
            "  isin: { header: Other, type: text, requiredValue: false }\nduplicates:");

    assertThatThrownBy(() -> loader.loadSourceContract(contract))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("not valid YAML")
        .hasMessageContaining("isin");
  }

  @Test
  void explicitNullListIsRejected() {
    assertRejected(
        MINIMAL_CSV.replace("normalize: [trim]", "normalize: ~"), "fields.isin.normalize");
  }

  @Test
  void rowRuleProblemsNameTheRule() {
    assertRejected(
        MINIMAL_CSV
            + "rowRules:\n  - { rule: lessThanOrEqual, left: isin, right: isin }\n"
            + "  - { rule: equals, left: isin, right: isin }\n",
        "rowRules[1].rule");
  }

  @Test
  void rejectsTextThatIsNotYamlOrNotMapping() {
    assertRejected("id: [unclosed", "(root)");
    assertRejected("- just\n- a list\n", "(root)");
    assertRejected("", "(root)");
  }

  @Test
  void refusesYamlThatNamesJavaTypes() {
    assertRejected(
        MINIMAL_CSV.replace("id: sample-csv", "id: !!java.net.URL [\"http://x\"]"), "(root)");
  }

  @Test
  void mappingContractNeedsItsSourceContractAndTarget() {
    assertThatThrownBy(() -> loader.loadMappingContract("id: m\nversion: v1\ntarget: t\n"))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("'sourceContract'");
    assertThatThrownBy(
            () -> loader.loadMappingContract("id: m\nversion: v1\nsourceContract: s-v1\n"))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("'target'");
  }

  private void assertMappingRejected(String yaml, String location) {
    assertThatThrownBy(() -> loader.loadMappingContract(yaml))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("'" + location + "'");
  }

  private void assertRejected(String yaml, String location) {
    assertThatThrownBy(() -> loader.loadSourceContract(yaml))
        .isInstanceOf(ContractFormatException.class)
        .hasMessageContaining("'" + location + "'");
  }

  private static CsvField field(SourceContract.Csv contract, String name) {
    return contract.fields().stream()
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static String committed(String contractName) {
    String resource = "/contracts/" + contractName + ".yaml";
    try (InputStream stream =
        Objects.requireNonNull(YamlContractLoaderTest.class.getResourceAsStream(resource))) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
