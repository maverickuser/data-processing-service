package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Duplicate names cannot be written in a YAML contract, because the loader rejects duplicate keys.
 * These cases build contracts directly to show the validator reports them too, as problems rather
 * than as an exception.
 */
class ContractValidatorTest {

  private static final DatasetUrn DATASET = new DatasetUrn("urn:bond-platform:dataset:sample");
  private static final CsvField ISIN =
      new CsvField(
          "isin", "ISIN No.", FieldType.TEXT, true, List.of("trim", "uppercase"), List.of());

  private final ContractValidator validator = new ContractValidator(RuleRegistry.standard());

  @Test
  void duplicateCsvFieldNameIsReported() {
    CsvField sameNameOtherHeader =
        new CsvField("isin", "Other", FieldType.TEXT, false, List.of("trim"), List.of());
    SourceContract contract =
        new SourceContract.Csv(
            new ContractId("sample-csv", "v1"),
            DATASET,
            100,
            List.of(ISIN, sameNameOtherHeader),
            List.of(),
            "isin");

    assertThat(validator.problemsIn(contract)).containsExactly("duplicate field 'isin'");
  }

  @Test
  void duplicateJsonNamesAndPathsAreReported() {
    JsonField issuer = new JsonField("issuer_name", "$.issuerName", FieldType.TEXT);
    JsonField sameName = new JsonField("issuer_name", "$.otherName", FieldType.TEXT);
    JsonField samePath = new JsonField("issuer", "$.issuerName", FieldType.TEXT);
    JsonField exchange = new JsonField("exchange_name", "exchangeName", FieldType.TEXT);
    JsonField exchangeAgain = new JsonField("exchange_name", "exchange", FieldType.TEXT);
    JsonField sameProperty = new JsonField("venue", "exchangeName", FieldType.TEXT);
    SourceContract contract =
        new SourceContract.Json(
            new ContractId("sample-json", "v1"),
            DATASET,
            100,
            List.of(issuer, sameName, samePath),
            List.of(
                new JsonCollection(
                    "listings",
                    "$.listingDetails[*]",
                    List.of(exchange, exchangeAgain, sameProperty)),
                new JsonCollection("issuer", "$.listingDetails[*]", List.of(exchange))));

    assertThat(validator.problemsIn(contract))
        .containsExactlyInAnyOrder(
            "duplicate scalar or collection name 'issuer'",
            "duplicate scalar or collection name 'issuer_name'",
            "duplicate scalar path '$.issuerName'",
            "duplicate collection path '$.listingDetails[*]'",
            "duplicate collection 'listings' field 'exchange_name'",
            "duplicate collection 'listings' field path 'exchangeName'");
  }
}
