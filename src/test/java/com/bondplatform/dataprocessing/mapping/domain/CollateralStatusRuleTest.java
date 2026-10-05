package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.EntryDisposition;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonCollectionEntry;
import com.bondplatform.dataprocessing.canonical.domain.JsonFieldReader;
import com.bondplatform.dataprocessing.canonical.domain.NsdlContract;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.mapping.domain.CollateralStatusRule.CollateralConflict;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollateralStatusRuleTest {

  private static final JsonFieldReader READER = new JsonFieldReader(RuleRegistry.standard());
  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final SourceReference SOURCE =
      new SourceReference(
          new JobId(UUID.fromString("0198f3a2-0000-7000-8000-000000000004")),
          "INE831R08076_instrument-details.json",
          "$.instrumentsVo.assetCover.securedFlag");

  private final CollateralStatusRule rule = new CollateralStatusRule(NsdlContract.PINNED.mapping());

  // U-UNSEC-01
  @Test
  void unsecuredClearsCoverageAndSuppressesAssets() {
    SecurityScalars scalars =
        scalars(
            "collateralStatus", new SecurityValue.Text("Unsecured"),
            "assetCoverageBasis", new SecurityValue.Text("Principal + Interest"),
            "couponType", new SecurityValue.Text("Simple"));

    CollateralStatusRule.Applied applied = rule.apply(scalars, collections());

    assertThat(applied.scalars().fields()).containsOnlyKeys("collateralStatus", "couponType");
    assertThat(applied.scalars().cleared())
        .containsExactlyInAnyOrder("assetCoverageBasis", "assetCoverage");
    assertThat(applied.collections().collateralAssets()).isEmpty();
    assertThat(applied.collections().listings()).hasSize(1);
  }

  @Test
  void otherOrMissingStatusChangesNothing() {
    SecurityScalars secured =
        scalars(
            "collateralStatus", new SecurityValue.Text("Secured"),
            "assetCoverage", new SecurityValue.Percentage(Percent.parse("100")));
    SecurityScalars other = scalars("collateralStatus", new SecurityValue.Text("Unsecured debt"));
    SecurityScalars none = scalars("couponType", new SecurityValue.Text("Simple"));

    for (SecurityScalars scalars : List.of(secured, other, none)) {
      CollateralStatusRule.Applied applied = rule.apply(scalars, collections());
      assertThat(applied.scalars()).isEqualTo(scalars);
      assertThat(applied.collections().collateralAssets()).hasSize(1);
    }
  }

  // AH-2: the owner decided the status matches in any letter case
  @Test
  void unsecuredInAnyCaseClearsCoverageAndAssets() {
    for (String status : List.of("UNSECURED", "unsecured", "UnSecured")) {
      CollateralStatusRule.Applied applied =
          rule.apply(scalars("collateralStatus", new SecurityValue.Text(status)), collections());

      assertThat(applied.scalars().cleared())
          .containsExactlyInAnyOrder("assetCoverageBasis", "assetCoverage");
      assertThat(applied.collections().collateralAssets()).isEmpty();
    }
  }

  @Test
  void upperCaseUnsecuredFileWithCoverageIsConflict() {
    JsonCanonicalField coverage =
        field("asset_coverage", FieldType.PERCENT, new SourceValue.Decimal(new BigDecimal("100")));

    CollateralStatusRule.Screened screened =
        rule.screen(List.of(status("unSECURED"), coverage), List.of());

    assertThat(screened.conflicts())
        .singleElement()
        .extracting(CollateralConflict::path)
        .isEqualTo("$.asset_coverage");
  }

  @Test
  void nonTextStatusIsNotUnsecured() {
    SecurityScalars scalars =
        scalars("collateralStatus", new SecurityValue.Percentage(Percent.parse("1")));

    assertThat(rule.apply(scalars, collections()).scalars()).isEqualTo(scalars);
    assertThat(CollateralStatusRule.isUnsecured(null)).isFalse();
    // AK-1: equalsIgnoreCase would match the long s (U+017F)
    String longS = Character.toString(0x017F);
    assertThat(CollateralStatusRule.isUnsecured("Un" + longS + "ecured")).isFalse();
  }

  // U-UNSEC-02: an Unsecured file that also supplies coverage or assets contradicts itself
  @Test
  void coverageAndAssetsBesideUnsecuredAreConflicts() {
    JsonCanonicalField coverage =
        field("asset_coverage", FieldType.PERCENT, new SourceValue.Decimal(new BigDecimal("100")));
    JsonCollectionEntry asset = asset("$.instrumentsVo.assetCover.assetList[0]", "Book Debts");
    JsonCollectionEntry listing =
        new JsonCollectionEntry(
            "listings",
            "$.listingDetails[0]",
            List.of(field("exchange_name", FieldType.TEXT, new SourceValue.Text("NSE"))),
            EntryDisposition.ACCEPTED);

    CollateralStatusRule.Screened screened =
        rule.screen(
            List.of(status("Unsecured"), coverage, text("asset_coverage_basis", "-")),
            List.of(asset, listing));

    assertThat(screened.scalars())
        .extracting(JsonCanonicalField::name)
        .containsExactly("collateral_status", "asset_coverage_basis");
    assertThat(screened.entries()).containsExactly(listing);
    assertThat(screened.conflicts())
        .containsExactly(
            new CollateralConflict(
                "$.asset_coverage",
                new SourceValue.Decimal(new BigDecimal("100")),
                new ValidationIssue(
                    ErrorCode.CONFLICTING_COLLATERAL_DATA,
                    "Asset coverage was supplied for an Unsecured instrument."),
                "Ignored the supplied coverage."),
            new CollateralConflict(
                "$.instrumentsVo.assetCover.assetList[0]",
                new SourceValue.Structured(
                    SourceValue.Structured.Kind.OBJECT, "{\"assetType\":\"Book Debts\"}"),
                new ValidationIssue(
                    ErrorCode.CONFLICTING_COLLATERAL_DATA,
                    "A collateral asset was supplied for an Unsecured instrument."),
                "Did not record the supplied asset."));
  }

  // U-UNSEC-02: nulls, placeholders, and an empty or skipped asset list are no conflict
  @Test
  void absentCoverageBesideUnsecuredIsNoConflict() {
    JsonCollectionEntry skipped =
        new JsonCollectionEntry(
            "collateral_assets",
            "$.instrumentsVo.assetCover.assetList[0]",
            List.of(text("asset_type", "N.A.")),
            EntryDisposition.SKIPPED);
    List<JsonCanonicalField> scalars =
        List.of(
            status("Unsecured"),
            field("asset_coverage", FieldType.PERCENT, new SourceValue.Null()),
            text("asset_coverage_basis", " "));

    CollateralStatusRule.Screened screened = rule.screen(scalars, List.of(skipped));

    assertThat(screened.conflicts()).isEmpty();
    assertThat(screened.scalars()).isEqualTo(scalars);
    assertThat(screened.entries()).containsExactly(skipped);
  }

  @Test
  void securedFileKeepsItsCoverageAndAssets() {
    JsonCanonicalField coverage = text("asset_coverage_basis", "Principal + Interest");
    JsonCollectionEntry asset = asset("$.instrumentsVo.assetCover.assetList[0]", "Book Debts");

    CollateralStatusRule.Screened screened =
        rule.screen(List.of(status("Secured"), coverage), List.of(asset));

    assertThat(screened.conflicts()).isEmpty();
    assertThat(screened.scalars()).containsExactly(status("Secured"), coverage);
    assertThat(screened.entries()).containsExactly(asset);
  }

  @Test
  void rejectsContractWithoutCollateralStatus() {
    MappingContract mapping = NsdlContract.PINNED.mapping();
    MappingContract contract =
        new MappingContract(
            mapping.id(),
            mapping.sourceContract(),
            new MappingContract.RecordMapping(
                InternalModel.SECURITIES, Map.of("coupon_type", "couponType")),
            List.of());

    assertThatThrownBy(() -> new CollateralStatusRule(contract))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not map collateralStatus");
  }

  private static SecurityCollections collections() {
    return new SecurityCollections(
        List.of(),
        List.of(new SecurityEntry<>(ISIN, new Listing("NSE", null), SOURCE)),
        List.of(),
        List.of(new SecurityEntry<>(ISIN, new CollateralAsset("Book Debts", null, null), SOURCE)));
  }

  private static SecurityScalars scalars(Object... namesAndValues) {
    Map<String, SecurityScalars.Field> fields = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      fields.put(
          (String) namesAndValues[i],
          new SecurityScalars.Field((SecurityValue) namesAndValues[i + 1], SOURCE));
    }
    return new SecurityScalars(fields);
  }

  private static JsonCollectionEntry asset(String path, String type) {
    return new JsonCollectionEntry(
        "collateral_assets",
        path,
        List.of(
            READER.read(
                "asset_type", path + ".assetType", FieldType.TEXT, new SourceValue.Text(type)),
            READER.read(
                "asset_value", path + ".assetValue", FieldType.TEXT, new SourceValue.Missing())),
        EntryDisposition.ACCEPTED);
  }

  private static JsonCanonicalField status(String value) {
    return text("collateral_status", value);
  }

  private static JsonCanonicalField text(String name, String value) {
    return field(name, FieldType.TEXT, new SourceValue.Text(value));
  }

  private static JsonCanonicalField field(String name, FieldType type, SourceValue value) {
    return READER.read(name, "$." + name, type, value);
  }
}
