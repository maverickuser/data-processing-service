package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CollectionExtractorTest {

  private final CollectionExtractor extractor =
      new CollectionExtractor(NsdlContract.CONTRACT, RuleRegistry.standard());

  // U-JSON-01 (collections)
  @Test
  void ratingsSampleYieldsCurrentAndEarlierRatingsKeptApart() {
    List<JsonCollectionEntry> entries = sample("INE831R08076_credit-ratings.json");

    assertThat(entries.stream().filter(entry -> entry.collection().equals("current_ratings")))
        .hasSize(2);
    assertThat(entries.stream().filter(entry -> entry.collection().equals("earlier_ratings")))
        .hasSize(15);
    assertThat(entries).allMatch(entry -> entry.disposition() == EntryDisposition.ACCEPTED);
    JsonCollectionEntry first = entries.get(0);
    assertThat(first.path()).isEqualTo("$.currentRatings[0]");
    assertThat(usable(first))
        .containsExactlyEntriesOf(
            ordered(
                "rating_agency_name", "INDIA RATING AND RESEARCH PVT. LTD",
                "rating", "AAA",
                "outlook", "Stable",
                "rating_action", "Reaffirm",
                "rating_date", "2019-02-15",
                "rating_change_date", "2026-09-23",
                "verification_date", "2026-09-24"));
    assertThat(field(first, "rating_date").path())
        .isEqualTo("$.currentRatings[0].creditRatingDate");
  }

  // U-JSON-01 (collections)
  @Test
  void earlierRatingWithPlaceholdersKeepsItsOtherValues() {
    JsonCollectionEntry earlier = sample("INE831R08076_credit-ratings.json").get(2);

    assertThat(earlier.path()).isEqualTo("$.earlierRatings[0]");
    assertThat(field(earlier, "rating_action").presence()).isEqualTo(FieldPresence.PLACEHOLDER);
    assertThat(usable(earlier)).doesNotContainKeys("rating_action", "rating_change_date");
    assertThat(usable(earlier)).containsEntry("rating_date", "2019-05-30");
  }

  // U-JSON-01 (collections)
  @Test
  void couponSamplesYieldCashFlowsWithExactAmounts() {
    List<JsonCollectionEntry> abhfl = sample("INE831R08076_coupon-details.json");
    List<JsonCollectionEntry> partial = sample("INE0O7U07046_coupon-details.json");

    assertThat(abhfl).hasSize(11).allMatch(entry -> entry.collection().equals("cash_flows"));
    assertThat(usable(abhfl.get(0)))
        .containsExactlyEntriesOf(
            ordered(
                "event_type", "Interest",
                "record_date", "2020-05-26",
                "due_date", "2020-06-10",
                "amount_payable", "89400.0",
                "payment_date", "2020-06-10"));
    assertThat(partial).hasSize(24);
    assertThat(usable(partial.get(2)))
        .containsEntry("event_type", "Partial Redemption")
        .containsEntry("amount_payable", "1340.41")
        .containsEntry("new_face_value", "8750");
  }

  // U-JSON-01 (collections)
  @Test
  void listingsSampleYieldsListingsAndEmptyAssetListYieldsNone() {
    assertThat(sample("INE831R08076_listings.json"))
        .extracting(entry -> usable(entry))
        .containsExactly(
            ordered("exchange_name", "NSE", "listing_date", "2019-06-14"),
            ordered("exchange_name", "BSE", "listing_date", "2019-06-13"));
    assertThat(sample("INE831R08076_instrument-details.json")).isEmpty();
  }

  // U-JSON-02
  @Test
  void fileWithoutSelectedPathsYieldsNothingAndNoError() {
    CollectionExtractor.CollectionExtraction extraction =
        extractor.extract(NsdlContract.sample("INE831R08076_redemptions.json"));

    assertThat(extraction.entries()).isEmpty();
    assertThat(extraction.structureIssues()).isEmpty();
  }

  // U-JSON-08
  @Test
  void objectWhereArrayIsExpectedRejectsThatCollectionOnly() {
    CollectionExtractor.CollectionExtraction extraction =
        extract(
            """
            {"currentRatings": {"creditRatingAgencyName": "ICRA LIMITED"},
             "listingDetails": [{"exchangeName": "BSE"}]}
            """);

    assertThat(extraction.structureIssues())
        .containsExactly(
            new StructureIssue(
                "$.currentRatings",
                new ValidationIssue(
                    ErrorCode.INVALID_TYPE,
                    "Expected an array at $.currentRatings but found object.")));
    assertThat(extraction.entries())
        .extracting(JsonCollectionEntry::collection)
        .containsExactly("listings");
  }

  @Test
  void entryThatIsNotAnObjectIsRejectedAndTheOthersContinue() {
    CollectionExtractor.CollectionExtraction extraction =
        extract("{\"listingDetails\": [\"BSE\", {\"exchangeName\": \"NSE\"}, null]}");

    assertThat(extraction.structureIssues())
        .extracting(StructureIssue::path)
        .containsExactly("$.listingDetails[0]", "$.listingDetails[2]");
    assertThat(extraction.structureIssues().get(1).issue().message())
        .isEqualTo("Expected an object at $.listingDetails[2] but found null.");
    assertThat(extraction.entries())
        .extracting(JsonCollectionEntry::path)
        .containsExactly("$.listingDetails[1]");
  }

  // U-JSON-09
  @Test
  void invalidFieldInsideAnEntryIsRejectedAlone() {
    JsonCollectionEntry entry =
        extract(
                """
                {"listingDetails": [{"exchangeName": "BSE", "listingDate": "31-02-2019"}]}
                """)
            .entries()
            .get(0);

    assertThat(entry.disposition()).isEqualTo(EntryDisposition.ACCEPTED);
    assertThat(usable(entry)).containsExactlyEntriesOf(ordered("exchange_name", "BSE"));
    assertThat(field(entry, "listing_date").errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_DATE);
    assertThat(field(entry, "listing_date").path()).isEqualTo("$.listingDetails[0].listingDate");
  }

  // U-JSON-10
  @Test
  void entryWithoutValidNonBlankValueIsSkippedAndKeepsItsErrors() {
    List<JsonCollectionEntry> entries =
        extract(
                """
                {"listingDetails": [
                  {"exchangeName": "-", "listingDate": "N.A."},
                  {"exchangeName": 7, "listingDate": null},
                  {},
                  {"exchangeName": " NSE "}
                ]}
                """)
            .entries();

    assertThat(entries)
        .extracting(JsonCollectionEntry::disposition)
        .containsExactly(
            EntryDisposition.SKIPPED,
            EntryDisposition.SKIPPED,
            EntryDisposition.SKIPPED,
            EntryDisposition.ACCEPTED);
    assertThat(field(entries.get(1), "exchange_name").errors())
        .extracting(ValidationIssue::code)
        .containsExactly(ErrorCode.INVALID_TYPE);
    assertThat(usable(entries.get(3))).containsExactlyEntriesOf(ordered("exchange_name", "NSE"));
  }

  @Test
  void missingOrNullCollectionSelectsNothing() {
    CollectionExtractor.CollectionExtraction extraction =
        extract("{\"listingDetails\": null, \"earlierRatings\": []}");

    assertThat(extraction.entries()).isEmpty();
    assertThat(extraction.structureIssues()).isEmpty();
  }

  private List<JsonCollectionEntry> sample(String fileName) {
    return extractor.extract(NsdlContract.sample(fileName)).entries();
  }

  private CollectionExtractor.CollectionExtraction extract(String json) {
    return extractor.extract(NsdlContract.parse(json));
  }

  private static JsonCanonicalField field(JsonCollectionEntry entry, String name) {
    return entry.fields().stream()
        .filter(field -> field.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** Returns the entry's usable fields and their parsed values, in contract order. */
  private static Map<String, @Nullable String> usable(JsonCollectionEntry entry) {
    Map<String, @Nullable String> usable = new LinkedHashMap<>();
    entry.fields().stream()
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
