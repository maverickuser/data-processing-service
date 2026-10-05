package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.mapping.domain.ScalarPrecedenceResolver.ResolvedScalar;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a request's winning canonical scalars into security field values, as the mapping contract
 * directs (LLD section 15.3).
 *
 * <p>The contract only renames; the internal model gives each field its type. Each value keeps the
 * job, file name, and JSONPath it came from, for the security's field sources.
 */
public final class SecurityFieldMapper {

  private final Map<String, String> fields;
  private final Map<String, FieldType> types;

  /**
   * Creates a mapper for a security mapping contract.
   *
   * @throws IllegalArgumentException if the contract targets another table or maps to a field the
   *     security does not have
   */
  public SecurityFieldMapper(MappingContract contract) {
    MappingContract.RecordMapping primary = contract.primary();
    if (!primary.target().equals(InternalModel.SECURITIES)) {
      throw new IllegalArgumentException(
          "Contract " + contract.id() + " does not target securities");
    }
    Map<String, FieldType> securityTypes =
        InternalModel.target(InternalModel.SECURITIES).orElseThrow().fields();
    for (String internal : primary.fields().values()) {
      if (!securityTypes.containsKey(internal)) {
        throw new IllegalArgumentException(
            "Contract " + contract.id() + " maps to unknown field " + internal);
      }
    }
    this.fields = primary.fields();
    this.types = securityTypes;
  }

  /**
   * Maps the winning scalars; a field without a winner is left out.
   *
   * @param jobId the request the values came from
   * @param winners the winning value of each canonical field, as {@link ScalarPrecedenceResolver}
   *     chose them
   */
  public SecurityScalars map(JobId jobId, Map<String, ResolvedScalar> winners) {
    Map<String, SecurityScalars.Field> mapped = new LinkedHashMap<>();
    fields.forEach(
        (canonical, internal) -> {
          ResolvedScalar winner = winners.get(canonical);
          if (winner != null) {
            String parsed =
                Objects.requireNonNull(
                    winner.field().parsedValue(), () -> canonical + " has no parsed value");
            mapped.put(
                internal,
                new SecurityScalars.Field(
                    value(Objects.requireNonNull(types.get(internal)), parsed),
                    new SourceReference(
                        jobId, fileName(winner.objectKey()), winner.field().path())));
          }
        });
    return new SecurityScalars(mapped);
  }

  /** Returns the typed value of a parsed canonical value. */
  static SecurityValue value(FieldType type, String parsed) {
    return switch (type) {
      case TEXT -> new SecurityValue.Text(parsed);
      case DATE -> new SecurityValue.Date(LocalDate.parse(parsed));
      case DECIMAL -> new SecurityValue.Decimal(new BigDecimal(parsed));
      case PERCENT -> new SecurityValue.Percentage(Percent.of(new BigDecimal(parsed)));
      case INTEGER ->
          throw new IllegalArgumentException("A security field cannot have type INTEGER");
    };
  }

  private static String fileName(String objectKey) {
    return objectKey.substring(objectKey.lastIndexOf('/') + 1);
  }
}
