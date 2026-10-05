package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityScalarsTest {

  private static final SourceReference SOURCE =
      new SourceReference(
          new JobId(UUID.fromString("0198f3a2-0000-7000-8000-000000000002")),
          "INE831R08076_coupon-details.json",
          "$.coupensVo.couponDetails.couponRate");
  private static final SecurityValue PERCENT_100 =
      new SecurityValue.Percentage(Percent.parse("100"));

  // U-SCAL-01
  @Test
  void onlyValuesThatDifferAreChanges() {
    SecurityScalars incoming =
        scalars(
            "couponRate", new SecurityValue.Percentage(Percent.parse("8.940")),
            "couponType", new SecurityValue.Text("Compound"),
            "originalFaceValue", new SecurityValue.Decimal(new BigDecimal("1000000.00")),
            "allotmentDate", new SecurityValue.Date(LocalDate.of(2019, 6, 10)));
    Map<String, SecurityValue> stored =
        Map.of(
            "couponRate", new SecurityValue.Percentage(Percent.parse("8.94")),
            "couponType", new SecurityValue.Text("Simple"),
            "originalFaceValue", new SecurityValue.Decimal(new BigDecimal("1000000")),
            "allotmentDate", new SecurityValue.Date(LocalDate.of(2019, 6, 10)));

    assertThat(incoming.changesFrom(stored).fields()).containsOnlyKeys("couponType");
  }

  // U-SCAL-01
  @Test
  void identicalValuesAreNoChangeAtAll() {
    SecurityScalars incoming = scalars("couponType", new SecurityValue.Text("Simple"));

    assertThat(
            incoming.changesFrom(Map.of("couponType", new SecurityValue.Text("Simple"))).isEmpty())
        .isTrue();
  }

  // U-SCAL-02: a field the request has no valid value for is absent, so the stored one stays
  @Test
  void storedFieldsWithoutIncomingValueAreLeftAlone() {
    SecurityScalars incoming = scalars("listingStatus", new SecurityValue.Text("Listed"));

    SecurityScalars changes =
        incoming.changesFrom(
            Map.of(
                "issuerName", new SecurityValue.Text("Acme Finance"),
                "couponRate", new SecurityValue.Percentage(Percent.parse("8.94"))));

    assertThat(changes.fields()).containsOnlyKeys("listingStatus");
    assertThat(Objects.requireNonNull(changes.fields().get("listingStatus")).source())
        .isEqualTo(SOURCE);
  }

  @Test
  void valueOfFieldNeverStoredIsChanged() {
    assertThat(
            scalars("couponType", new SecurityValue.Text("Simple")).changesFrom(Map.of()).fields())
        .containsOnlyKeys("couponType");
  }

  // U-UNSEC-01: clearing removes a stored value, and is no change when nothing is stored
  @Test
  void clearedFieldIsChangeOnlyWhenSomethingIsStored() {
    SecurityScalars incoming =
        scalars(
                "collateralStatus", new SecurityValue.Text("Unsecured"),
                "assetCoverageBasis", new SecurityValue.Text("Principal + Interest"))
            .clearing(Set.of("assetCoverageBasis", "assetCoverage"));

    assertThat(incoming.fields()).containsOnlyKeys("collateralStatus");
    assertThat(incoming.changesFrom(Map.of("assetCoverage", PERCENT_100)).cleared())
        .containsExactly("assetCoverage");
    SecurityScalars none =
        incoming.changesFrom(Map.of("collateralStatus", new SecurityValue.Text("Unsecured")));
    assertThat(none.isEmpty()).isTrue();
  }

  @Test
  void fieldCannotBeBothSetAndCleared() {
    Map<String, SecurityScalars.Field> fields =
        Map.of("assetCoverage", new SecurityScalars.Field(PERCENT_100, SOURCE));
    Set<String> cleared = Set.of("assetCoverage");

    assertThatThrownBy(() -> new SecurityScalars(fields, cleared))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Field assetCoverage cannot be both set and cleared");
  }

  @Test
  void textIsComparedExactly() {
    assertThat(new SecurityValue.Text("Non PSU")).isNotEqualTo(new SecurityValue.Text("NON PSU"));
  }

  @Test
  void decimalsAreEqualWhenNumericallyEqual() {
    SecurityValue.Decimal plain = new SecurityValue.Decimal(new BigDecimal("89400"));
    SecurityValue.Decimal scaled = new SecurityValue.Decimal(new BigDecimal("89400.00"));

    assertThat(plain).isEqualTo(scaled).hasSameHashCodeAs(scaled);
    assertThat(plain).isNotEqualTo(new SecurityValue.Decimal(new BigDecimal("89400.01")));
    assertThat(plain).isNotEqualTo(new SecurityValue.Text("89400"));
  }

  @Test
  void rejectsMissingParts() {
    assertThatThrownBy(() -> new SecurityScalars.Field(new SecurityValue.Text("x"), missing()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SecurityScalars.Field(missing(), SOURCE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SecurityValue.Text(missing()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SecurityValue.Date(missing()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SecurityValue.Decimal(missing()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SecurityValue.Percentage(missing()))
        .isInstanceOf(NullPointerException.class);
  }

  @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
  private static <T> T missing() {
    return null;
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
}
