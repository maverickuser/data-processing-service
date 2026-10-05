package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.BhavcopyContract;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalizer;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.JsonRead;
import com.bondplatform.dataprocessing.canonical.domain.NsdlContract;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.mapping.domain.ScalarPrecedenceResolver.ScalarSource;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityFieldMapperTest {

  private static final JobId JOB =
      new JobId(UUID.fromString("0198f3a2-0000-7000-8000-000000000001"));
  private static final List<String> SAMPLES =
      List.of(
          "INE831R08076_isin-details.json",
          "INE831R08076_instrument-details.json",
          "INE831R08076_coupon-details.json",
          "INE831R08076_credit-ratings.json",
          "INE831R08076_listings.json",
          "INE831R08076_redemptions.json");

  private final SecurityFieldMapper mapper = new SecurityFieldMapper(NsdlContract.PINNED.mapping());

  // U-MAP-03
  @Test
  void sampleFilesMapToTheSecurityFields() {
    SecurityScalars scalars = mapper.map(JOB, ScalarPrecedenceResolver.resolve(samples()));

    assertThat(values(scalars))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "issuerName", new SecurityValue.Text("ADITYA BIRLA HOUSING FINANCE LIMITED"),
                "issuerOwnershipType", new SecurityValue.Text("Non PSU"),
                "instrumentType", new SecurityValue.Text("Debentures"),
                "allotmentDate", new SecurityValue.Date(LocalDate.of(2019, 6, 10)),
                "redemptionDate", new SecurityValue.Date(LocalDate.of(2029, 6, 8)),
                "originalFaceValue", new SecurityValue.Decimal(new BigDecimal("1000000")),
                "collateralStatus", new SecurityValue.Text("Unsecured"),
                "couponRate", new SecurityValue.Percentage(Percent.parse("8.94")),
                "couponType", new SecurityValue.Text("Simple"),
                "listingStatus", new SecurityValue.Text("Listed")));
  }

  // U-MAP-03: each value keeps its own file and JSONPath
  @Test
  void eachFieldRecordsTheFileAndPathItCameFrom() {
    SecurityScalars scalars = mapper.map(JOB, ScalarPrecedenceResolver.resolve(samples()));

    assertThat(Objects.requireNonNull(scalars.fields().get("originalFaceValue")).source())
        .isEqualTo(
            new SourceReference(
                JOB,
                "INE831R08076_instrument-details.json",
                "$.instrumentsVo.instruments.issuePrice"));
    assertThat(Objects.requireNonNull(scalars.fields().get("couponRate")).source())
        .isEqualTo(
            new SourceReference(
                JOB, "INE831R08076_coupon-details.json", "$.coupensVo.couponDetails.couponRate"));
  }

  @Test
  void noWinnersMapToNothing() {
    assertThat(mapper.map(JOB, Map.of()).isEmpty()).isTrue();
  }

  @Test
  void rejectsContractForAnotherTarget() {
    assertThatThrownBy(() -> new SecurityFieldMapper(BhavcopyContract.MAPPING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not target securities");
  }

  @Test
  void rejectsContractMappingToUnknownField() {
    MappingContract contract =
        new MappingContract(
            NsdlContract.PINNED.mapping().id(),
            "nsdl-security-json-v1",
            new MappingContract.RecordMapping(
                InternalModel.SECURITIES, Map.of("issuer_name", "issuer")),
            List.of());

    assertThatThrownBy(() -> new SecurityFieldMapper(contract))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maps to unknown field issuer");
  }

  @Test
  void parsedValuesBecomeTypedValues() {
    assertThat(SecurityFieldMapper.value(FieldType.DECIMAL, "89400.00"))
        .isEqualTo(new SecurityValue.Decimal(new BigDecimal("89400")));
    assertThat(SecurityFieldMapper.value(FieldType.PERCENT, "100"))
        .isEqualTo(new SecurityValue.Percentage(Percent.parse("100%")));
    assertThatThrownBy(() -> SecurityFieldMapper.value(FieldType.INTEGER, "1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A security field cannot have type INTEGER");
  }

  /** The six samples as one request, each file a minute later than the one before. */
  private static List<ScalarSource> samples() {
    JsonCanonicalizer canonicalizer =
        new JsonCanonicalizer(NsdlContract.CONTRACT, RuleRegistry.standard());
    List<ScalarSource> sources = new ArrayList<>();
    Instant time = Instant.parse("2026-10-01T10:00:00Z");
    for (String sample : SAMPLES) {
      JsonFileCanonical.Read read =
          (JsonFileCanonical.Read)
              canonicalizer.canonicalize(
                  Isin.of("INE831R08076"), new JsonRead.Parsed(NsdlContract.sample(sample)));
      sources.add(new ScalarSource("nsdl/job-1/" + sample, time, read.scalars()));
      time = time.plusSeconds(60);
    }
    return sources;
  }

  private static Map<String, SecurityValue> values(SecurityScalars scalars) {
    Map<String, SecurityValue> values = new HashMap<>();
    scalars.fields().forEach((name, field) -> values.put(name, field.value()));
    return values;
  }
}
