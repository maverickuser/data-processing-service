package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonFieldReader;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.mapping.domain.ScalarPrecedenceResolver.ResolvedScalar;
import com.bondplatform.dataprocessing.mapping.domain.ScalarPrecedenceResolver.ScalarSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ScalarPrecedenceResolverTest {

  private static final Instant EARLY = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant LATE = Instant.parse("2026-10-01T11:00:00Z");
  private static final JsonFieldReader READER = new JsonFieldReader(RuleRegistry.standard());

  // U-SCAL-03
  @Test
  void laterLastModifiedWinsWhateverTheListOrder() {
    ScalarSource late = source("in/b.json", LATE, text("coupon_type", "Simple"));
    ScalarSource early = source("in/a.json", EARLY, text("coupon_type", "Compound"));

    Map<String, ResolvedScalar> winners = ScalarPrecedenceResolver.resolve(List.of(late, early));

    assertThat(Objects.requireNonNull(winners.get("coupon_type")).field().parsedValue())
        .isEqualTo("Simple");
    assertThat(Objects.requireNonNull(winners.get("coupon_type")).objectKey())
        .isEqualTo("in/b.json");
  }

  // U-SCAL-03
  @Test
  void fullObjectKeyBreaksTimestampTies() {
    ScalarSource second = source("in/z/coupon.json", EARLY, text("coupon_type", "Second"));
    ScalarSource first = source("in/a/coupon.json", EARLY, text("coupon_type", "First"));

    assertThat(ScalarPrecedenceResolver.resolve(List.of(second, first)).get("coupon_type"))
        .extracting(ResolvedScalar::objectKey)
        .isEqualTo("in/z/coupon.json");
    assertThat(ScalarPrecedenceResolver.resolve(List.of(first, second)).get("coupon_type"))
        .extracting(ResolvedScalar::objectKey)
        .isEqualTo("in/z/coupon.json");
  }

  // U-SCAL-03, U-SCAL-02
  @Test
  void precedenceIsPerFieldAndNoValueNeverWins() {
    ScalarSource early =
        source(
            "in/a.json",
            EARLY,
            text("issuer_name", "Acme Finance"),
            text("instrument_type", "Debentures"),
            text("coupon_type", "Simple"));
    ScalarSource late =
        source(
            "in/b.json",
            LATE,
            text("issuer_name", "-"),
            READER.read(
                "instrument_type", "$.instrumentType", FieldType.TEXT, new SourceValue.Null()),
            READER.read(
                "coupon_type",
                "$.coupensVo.couponDetails.couponType",
                FieldType.TEXT,
                new SourceValue.Decimal(BigDecimal.ONE)),
            text("listing_status", "Listed"));

    Map<String, ResolvedScalar> winners = ScalarPrecedenceResolver.resolve(List.of(early, late));

    assertThat(winners)
        .containsOnlyKeys("issuer_name", "instrument_type", "coupon_type", "listing_status");
    assertThat(Objects.requireNonNull(winners.get("issuer_name")).objectKey())
        .isEqualTo("in/a.json");
    assertThat(Objects.requireNonNull(winners.get("instrument_type")).objectKey())
        .isEqualTo("in/a.json");
    assertThat(Objects.requireNonNull(winners.get("coupon_type")).field().parsedValue())
        .isEqualTo("Simple");
    assertThat(Objects.requireNonNull(winners.get("listing_status")).objectKey())
        .isEqualTo("in/b.json");
  }

  @Test
  void noFilesOrNoUsableValuesGiveNoWinners() {
    assertThat(ScalarPrecedenceResolver.resolve(List.of())).isEmpty();
    assertThat(
            ScalarPrecedenceResolver.resolve(
                List.of(source("in/a.json", EARLY, text("issuer_name", "N.A.")))))
        .isEmpty();
  }

  private static ScalarSource source(
      String key, Instant lastModified, JsonCanonicalField... scalars) {
    return new ScalarSource(key, lastModified, List.of(scalars));
  }

  private static JsonCanonicalField text(String name, String value) {
    return READER.read(name, "$." + name, FieldType.TEXT, new SourceValue.Text(value));
  }
}
