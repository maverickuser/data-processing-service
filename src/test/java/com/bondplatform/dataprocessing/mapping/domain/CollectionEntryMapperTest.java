package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.EntryDisposition;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalizer;
import com.bondplatform.dataprocessing.canonical.domain.JsonCollectionEntry;
import com.bondplatform.dataprocessing.canonical.domain.JsonFieldReader;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.JsonRead;
import com.bondplatform.dataprocessing.canonical.domain.NsdlContract;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.mapping.domain.CollectionEntryMapper.EntrySource;
import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.Rating;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionEntryMapperTest {

  private static final Isin ISIN = Isin.of("INE831R08076");
  private static final JobId JOB =
      new JobId(UUID.fromString("0198f3a2-0000-7000-8000-000000000003"));
  private static final JsonFieldReader READER = new JsonFieldReader(RuleRegistry.standard());

  private final CollectionEntryMapper mapper =
      new CollectionEntryMapper(NsdlContract.PINNED.mapping());

  // U-MAP-04
  @Test
  void currentAndEarlierRatingsKeepTheirSourceCategory() {
    SecurityCollections collections =
        mapper.map(ISIN, JOB, List.of(sample("INE831R08076_credit-ratings.json")));

    assertThat(collections.ratings())
        .extracting(entry -> entry.value().sourceCategory())
        .containsOnly(Rating.SourceCategory.CURRENT, Rating.SourceCategory.EARLIER);
    assertThat(categoryCount(collections, Rating.SourceCategory.CURRENT)).isEqualTo(2);
    assertThat(categoryCount(collections, Rating.SourceCategory.EARLIER)).isEqualTo(13);
    assertThat(collections.ratings().getFirst())
        .isEqualTo(
            new SecurityEntry<>(
                ISIN,
                new Rating(
                    Rating.SourceCategory.CURRENT,
                    "INDIA RATING AND RESEARCH PVT. LTD",
                    "AAA",
                    "Stable",
                    "Reaffirm",
                    LocalDate.of(2019, 2, 15),
                    LocalDate.of(2026, 9, 23),
                    LocalDate.of(2026, 9, 24)),
                new SourceReference(
                    JOB, "INE831R08076_credit-ratings.json", "$.currentRatings[0]")));
  }

  // U-MAP-04: the same values in both lists are two observations
  @Test
  void sameRatingInBothListsIsTwoEntries() {
    JsonCanonicalField agency = text("rating_agency_name", "ICRA LIMITED");
    SecurityCollections collections =
        mapper.map(
            ISIN,
            JOB,
            List.of(
                source(
                    "nsdl/a.json",
                    entry("current_ratings", "$.currentRatings[0]", agency),
                    entry("earlier_ratings", "$.earlierRatings[0]", agency))));

    assertThat(collections.ratings())
        .extracting(SecurityEntry::value)
        .containsExactly(
            rating(Rating.SourceCategory.CURRENT, "ICRA LIMITED"),
            rating(Rating.SourceCategory.EARLIER, "ICRA LIMITED"));
  }

  @Test
  void sampleCashFlowsAndListingsAreMapped() {
    SecurityCollections collections =
        mapper.map(
            ISIN,
            JOB,
            List.of(
                sample("INE831R08076_coupon-details.json"), sample("INE831R08076_listings.json")));

    assertThat(collections.cashFlows()).hasSize(11);
    assertThat(collections.cashFlows().getLast().value())
        .isEqualTo(
            new CashFlow(
                "Full Redemption",
                LocalDate.of(2029, 5, 24),
                LocalDate.of(2029, 6, 8),
                new BigDecimal("1000000.0"),
                LocalDate.of(2029, 6, 8),
                null));
    assertThat(collections.listings())
        .extracting(SecurityEntry::value)
        .containsExactly(
            new Listing("NSE", LocalDate.of(2019, 6, 14)),
            new Listing("BSE", LocalDate.of(2019, 6, 13)));
    assertThat(collections.ratings()).isEmpty();
    assertThat(collections.collateralAssets()).isEmpty();
  }

  // U-COLL-01: missing, null, placeholder, and invalid are all "no value"; 89400 equals
  // "89,400.00",
  // and the first entry is stored with its own scale (review AH-1)
  @Test
  void entriesDifferingOnlyInHowValuesAreAbsentAreOneEntry() {
    JsonCanonicalField interest = text("event_type", "Interest");
    JsonCanonicalField due = text("due_date", "08-06-2027", FieldType.DATE);
    SecurityCollections collections =
        mapper.map(
            ISIN,
            JOB,
            List.of(
                source(
                    "nsdl/a.json",
                    entry(
                        "cash_flows",
                        "$.cf[0]",
                        interest,
                        due,
                        text("amount_payable", "89,400.00", FieldType.DECIMAL),
                        text("record_date", "-", FieldType.DATE)),
                    entry(
                        "cash_flows",
                        "$.cf[1]",
                        interest,
                        due,
                        READER.read(
                            "amount_payable",
                            "$.cf[1].amountPayable",
                            FieldType.DECIMAL,
                            new SourceValue.Decimal(new BigDecimal("89400"))),
                        READER.read(
                            "record_date",
                            "$.cf[1].recordDate",
                            FieldType.DATE,
                            new SourceValue.Null())),
                    entry(
                        "cash_flows",
                        "$.cf[2]",
                        interest,
                        due,
                        text("amount_payable", "89400.0", FieldType.DECIMAL),
                        text("record_date", "32-13-2026", FieldType.DATE))),
                source(
                    "nsdl/b.json",
                    entry(
                        "cash_flows",
                        "$.cf[0]",
                        interest,
                        due,
                        text("amount_payable", "89400", FieldType.DECIMAL)))));

    assertThat(collections.cashFlows())
        .containsExactly(
            new SecurityEntry<>(
                ISIN,
                new CashFlow(
                    "Interest",
                    null,
                    LocalDate.of(2027, 6, 8),
                    new BigDecimal("89400.00"),
                    null,
                    null),
                new SourceReference(JOB, "a.json", "$.cf[0]")));
  }

  // U-COLL-02
  @Test
  void changedCombinationOfValuesIsAnotherEntry() {
    SecurityCollections collections =
        mapper.map(
            ISIN,
            JOB,
            List.of(
                source(
                    "nsdl/a.json",
                    entry("listings", "$.l[0]", text("exchange_name", "NSE")),
                    entry(
                        "listings",
                        "$.l[1]",
                        text("exchange_name", "NSE"),
                        text("listing_date", "14-06-2019", FieldType.DATE)))));

    assertThat(collections.listings())
        .extracting(SecurityEntry::value)
        .containsExactly(new Listing("NSE", null), new Listing("NSE", LocalDate.of(2019, 6, 14)));
  }

  @Test
  void skippedEntriesAreLeftOutAndAssetsAreMapped() {
    JsonCollectionEntry skipped =
        new JsonCollectionEntry(
            "collateral_assets",
            "$.assets[0]",
            List.of(text("asset_type", "-")),
            EntryDisposition.SKIPPED);
    SecurityCollections collections =
        mapper.map(
            ISIN,
            JOB,
            List.of(
                new EntrySource(
                    "a.json",
                    List.of(
                        skipped,
                        entry(
                            "collateral_assets",
                            "$.assets[1]",
                            text("asset_type", "Book Debts / Receivables"),
                            text("collateral_description", "Loan receivables"))))));

    assertThat(collections.collateralAssets())
        .containsExactly(
            new SecurityEntry<>(
                ISIN,
                new CollateralAsset("Book Debts / Receivables", "Loan receivables", null),
                new SourceReference(JOB, "a.json", "$.assets[1]")));
    assertThat(collections.isEmpty()).isFalse();
    assertThat(collections.withoutCollateralAssets().isEmpty()).isTrue();
  }

  @Test
  void entryOfUnmappedCollectionFails() {
    List<EntrySource> sources =
        List.of(source("a.json", entry("bonds", "$.bonds[0]", text("issuer", "x"))));

    assertThatThrownBy(() -> mapper.map(ISIN, JOB, sources))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No mapping for collection bonds");
  }

  @Test
  void rejectsCollectionTargetingAnotherTable() {
    MappingContract mapping = NsdlContract.PINNED.mapping();
    MappingContract contract =
        new MappingContract(
            mapping.id(),
            mapping.sourceContract(),
            mapping.primary(),
            List.of(
                new MappingContract.CollectionMapping(
                    "listings", InternalModel.SECURITIES, Map.of(), Map.of())));

    assertThatThrownBy(() -> new CollectionEntryMapper(contract))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Collection listings targets securities_data.securities");
  }

  private static long categoryCount(
      SecurityCollections collections, Rating.SourceCategory category) {
    return collections.ratings().stream()
        .filter(entry -> entry.value().sourceCategory() == category)
        .count();
  }

  private static Rating rating(Rating.SourceCategory category, String agency) {
    return new Rating(category, agency, null, null, null, null, null, null);
  }

  private static EntrySource sample(String fileName) {
    JsonFileCanonical.Read read =
        (JsonFileCanonical.Read)
            new JsonCanonicalizer(NsdlContract.CONTRACT, RuleRegistry.standard())
                .canonicalize(ISIN, new JsonRead.Parsed(NsdlContract.sample(fileName)));
    return new EntrySource("nsdl/job-1/" + fileName, read.entries());
  }

  private static EntrySource source(String key, JsonCollectionEntry... entries) {
    return new EntrySource(key, List.of(entries));
  }

  private static JsonCollectionEntry entry(
      String collection, String path, JsonCanonicalField... fields) {
    return new JsonCollectionEntry(collection, path, List.of(fields), EntryDisposition.ACCEPTED);
  }

  private static JsonCanonicalField text(String name, String value) {
    return text(name, value, FieldType.TEXT);
  }

  private static JsonCanonicalField text(String name, String value, FieldType type) {
    return READER.read(name, "$." + name, type, new SourceValue.Text(value));
  }
}
