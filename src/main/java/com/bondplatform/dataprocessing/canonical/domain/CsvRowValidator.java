package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.ExactNumbers;
import com.bondplatform.dataprocessing.contract.domain.FieldResult;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.Normalizer;
import com.bondplatform.dataprocessing.contract.domain.NumberValidator;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns one CSV data record into a {@link CanonicalRecord} using the source contract's field and
 * row rules (LLD sections 5.2 and 5.3).
 *
 * <p>Every field is normalized, parsed, and validated on its own, and every price comparison whose
 * fields are both valid is then checked, so a row reports all of its applicable problems, not only
 * the first.
 */
public final class CsvRowValidator {

  private final List<FieldRules> fields;
  private final List<PriceConsistencyRule> rowRules;

  /**
   * Prepares the rules of a contract that has already passed contract validation.
   *
   * @throws IllegalArgumentException if a field has a type CSV does not support
   */
  public CsvRowValidator(SourceContract.Csv contract, RuleRegistry registry) {
    this.fields =
        contract.fields().stream()
            .map(
                field ->
                    new FieldRules(
                        field,
                        registry.normalizerFor(field.normalize()),
                        registry.numberValidatorFor(field.validate())))
            .toList();
    this.rowRules = contract.rowRules().stream().map(PriceConsistencyRule::new).toList();
  }

  /**
   * Validates one record.
   *
   * @throws IllegalArgumentException if the row lacks a field the contract selects, which the file
   *     reader never allows
   */
  public CanonicalRecord validate(CsvRow row) {
    Map<String, CanonicalField> canonical = new LinkedHashMap<>();
    for (FieldRules rules : fields) {
      CsvCell cell = row.cells().get(rules.field().name());
      if (cell == null) {
        throw new IllegalArgumentException(
            "Record " + row.recordNumber() + " has no cell for " + rules.field().name());
      }
      canonical.put(rules.field().name(), rules.validate(cell));
    }
    List<ValidationIssue> rowErrors =
        rowRules.stream().map(rule -> rule.check(canonical)).flatMap(Optional::stream).toList();
    return new CanonicalRecord(row.recordNumber(), canonical, rowErrors);
  }

  /** One field's contract entry with its normalizer chain and number validators resolved. */
  private record FieldRules(CsvField field, Normalizer normalizer, NumberValidator validator) {

    FieldRules {
      switch (field.type()) {
        case TEXT, DECIMAL, INTEGER -> {}
        default ->
            throw new IllegalArgumentException(
                "CSV field " + field.name() + " has unsupported type " + field.type());
      }
    }

    CanonicalField validate(CsvCell cell) {
      return switch (normalizer.normalize(cell.value())) {
        case FieldResult.Valid<String> valid ->
            field.type() == FieldType.TEXT
                ? result(cell, valid.value(), valid.value(), List.of())
                : number(cell, valid.value());
        case FieldResult.NoValue<String> ignored ->
            result(
                cell,
                null,
                null,
                field.requiredValue()
                    ? List.of(
                        new ValidationIssue(
                            ErrorCode.REQUIRED_VALUE_MISSING, "A value is required."))
                    : List.of());
        case FieldResult.Rejected<String> rejected -> result(cell, null, null, issue(rejected));
      };
    }

    /** Parses exactly, then applies the contract's number validators to the parsed value. */
    private CanonicalField number(CsvCell cell, String normalized) {
      FieldResult<?> parsed =
          field.type() == FieldType.INTEGER
              ? ExactNumbers.wholeNumber(normalized)
              : ExactNumbers.decimal(normalized);
      if (parsed instanceof FieldResult.Rejected<?> rejected) {
        return result(cell, normalized, null, issue(rejected));
      }
      Object value = ((FieldResult.Valid<?>) parsed).value();
      BigDecimal number =
          value instanceof BigInteger whole ? new BigDecimal(whole) : (BigDecimal) value;
      List<ValidationIssue> errors =
          validator.validate(number).stream()
              .map(violation -> new ValidationIssue(violation.code(), violation.message()))
              .toList();
      return result(cell, normalized, number.toPlainString(), errors);
    }

    private static List<ValidationIssue> issue(FieldResult.Rejected<?> rejected) {
      return List.of(new ValidationIssue(rejected.code(), rejected.message()));
    }

    private CanonicalField result(
        CsvCell cell,
        @Nullable String normalized,
        @Nullable String parsed,
        List<ValidationIssue> errors) {
      return new CanonicalField(
          field.name(),
          field.header(),
          cell.columnIndex(),
          cell.value(),
          normalized,
          parsed,
          field.type(),
          errors.isEmpty() ? ValidationStatus.PASSED : ValidationStatus.FAILED,
          errors);
    }
  }
}
