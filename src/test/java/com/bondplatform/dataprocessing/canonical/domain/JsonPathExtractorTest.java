package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonArray;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonBoolean;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNull;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNumber;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonObject;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonString;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonPathExtractorTest {

  private static final JsonObject LISTING_BSE =
      new JsonObject(Map.of("exchangeName", new JsonString("BSE")));
  private static final JsonObject LISTING_NSE =
      new JsonObject(Map.of("exchangeName", new JsonString("NSE")));

  private static final JsonObject DOCUMENT =
      new JsonObject(
          Map.of(
              "issuerName", new JsonString("Acme Finance"),
              "instrumentsVo",
                  new JsonObject(
                      Map.of(
                          "instruments",
                          new JsonObject(
                              Map.of(
                                  "issuePrice", new JsonNumber(new BigDecimal("1000")),
                                  "allotmentDate", new JsonNull())))),
              "coupensVo", new JsonNull(),
              "listingStatus", new JsonBoolean(true),
              "listingDetails", new JsonArray(List.of(LISTING_BSE, LISTING_NSE)),
              "earlierRatings", new JsonArray(List.of()),
              "currentRatings", new JsonObject(Map.of()),
              "instrumentType", new JsonString("NCD")));

  @Test
  void findsScalarAtRootAndNested() {
    assertThat(scalar("$.issuerName"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Text("Acme Finance")));
    assertThat(scalar("$.instrumentsVo.instruments.issuePrice"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Decimal(new BigDecimal("1000"))));
    assertThat(scalar("$.listingStatus"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Bool(true)));
  }

  @Test
  void keepsExplicitNullApartFromMissingProperty() {
    assertThat(scalar("$.instrumentsVo.instruments.allotmentDate"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Null()));
    assertThat(scalar("$.instrumentsVo.instruments.redemptionDate"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Missing()));
  }

  @Test
  void scalarUnderMissingOrNullObjectIsMissing() {
    assertThat(scalar("$.coupensVo.couponDetails.couponRate"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Missing()));
    assertThat(scalar("$.assetCover.securedFlag"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Missing()));
  }

  // U-JSON-03
  @Test
  void propertyNamesMatchCaseSensitively() {
    assertThat(scalar("$.IssuerName")).isEqualTo(new JsonMatch.Found(new SourceValue.Missing()));
    assertThat(scalar("$.instrumentsvo.instruments.issuePrice"))
        .isEqualTo(new JsonMatch.Found(new SourceValue.Missing()));
    assertThat(collection("$.ListingDetails[*]")).isEqualTo(new JsonMatch.Absent());
  }

  @Test
  void reportsObjectOrArrayWhereScalarIsSelected() {
    assertThat(scalar("$.listingDetails"))
        .isEqualTo(
            new JsonMatch.Found(new SourceValue.Structured(SourceValue.Structured.Kind.ARRAY)));
    assertThat(scalar("$.currentRatings"))
        .isEqualTo(
            new JsonMatch.Found(new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT)));
  }

  @Test
  void valueOfWrongKindOnTheWayIsStructuralError() {
    assertThat(scalar("$.issuerName.first"))
        .isEqualTo(new JsonMatch.WrongStructure("$.issuerName", "object", "string"));
    assertThat(scalar("$.listingDetails.exchangeName"))
        .isEqualTo(new JsonMatch.WrongStructure("$.listingDetails", "object", "array"));
    assertThat(collection("$.instrumentType.assets[*]"))
        .isEqualTo(new JsonMatch.WrongStructure("$.instrumentType", "object", "string"));
  }

  @Test
  void rootThatIsNotAnObjectIsStructuralError() {
    JsonValue root = new JsonArray(List.of());

    assertThat(JsonPathExtractor.scalar(root, JsonPath.parse("$.issuerName")))
        .isEqualTo(new JsonMatch.WrongStructure("$", "object", "array"));
  }

  @Test
  void findsEveryCollectionEntryWithItsPath() {
    assertThat(collection("$.listingDetails[*]"))
        .isEqualTo(
            new JsonMatch.Entries(
                List.of(
                    new JsonMatch.Entry("$.listingDetails[0]", LISTING_BSE),
                    new JsonMatch.Entry("$.listingDetails[1]", LISTING_NSE))));
    assertThat(collection("$.earlierRatings[*]")).isEqualTo(new JsonMatch.Entries(List.of()));
  }

  @Test
  void missingOrNullCollectionIsAbsent() {
    assertThat(collection("$.coupensVo.cashFlowScheduleDetails.cashFlowSchedule[*]"))
        .isEqualTo(new JsonMatch.Absent());
    assertThat(collection("$.coupensVo[*]")).isEqualTo(new JsonMatch.Absent());
    assertThat(collection("$.instrumentsVo.assetCover.assetList[*]"))
        .isEqualTo(new JsonMatch.Absent());
  }

  // LLD 13.7: an object where currentRatings needs an array quarantines that collection
  @Test
  void collectionThatIsNotAnArrayIsStructuralError() {
    assertThat(collection("$.currentRatings[*]"))
        .isEqualTo(new JsonMatch.WrongStructure("$.currentRatings", "array", "object"));
    assertThat(collection("$.issuerName[*]"))
        .isEqualTo(new JsonMatch.WrongStructure("$.issuerName", "array", "string"));
    assertThat(collection("$.instrumentsVo.instruments.issuePrice[*]"))
        .isEqualTo(
            new JsonMatch.WrongStructure(
                "$.instrumentsVo.instruments.issuePrice", "array", "number"));
  }

  @Test
  void pathMustSuitTheKindOfSelection() {
    assertThatThrownBy(() -> collection("$.issuerName"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("$.issuerName selects a scalar, not a collection");
    assertThatThrownBy(() -> scalar("$.listingDetails[*]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("$.listingDetails[*] selects a collection, not a scalar");
  }

  private static JsonMatch.Scalar scalar(String path) {
    return JsonPathExtractor.scalar(DOCUMENT, JsonPath.parse(path));
  }

  private static JsonMatch.Collection collection(String path) {
    return JsonPathExtractor.collection(DOCUMENT, JsonPath.parse(path));
  }
}
