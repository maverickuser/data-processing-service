package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.DateParser;
import com.bondplatform.dataprocessing.contract.domain.ExactNumbers;
import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.FieldResult;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.Normalizer;
import com.bondplatform.dataprocessing.contract.domain.NumberValidator;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.contract.domain.TextFieldReader;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads one selected JSON value by its contract type (LLD section 13.4). The rules are the same for
 * every JSON dataset, so they are fixed here rather than named in the contract.
 *
 * <ul>
 *   <li>Missing, {@code null}, blank, {@code -}, and {@code N.A.} are "no update", never errors.
 *   <li>Text accepts JSON strings only; surrounding whitespace is removed and case is kept.
 *   <li>Dates accept strings in {@code DD-MM-YYYY} or {@code YYYY-MM-DD} and become ISO dates.
 *   <li>Decimals accept JSON numbers, or strings with valid Western or Indian comma grouping; a
 *       percent may also end in {@code %}. Both must not be negative; zero is valid. A percent is
 *       in percentage points: {@code "8.94%"} is 8.94.
 * </ul>
 *
 * <p>A value of the wrong JSON kind is {@code INVALID_TYPE}; it is never converted.
 */
public final class JsonFieldReader {

  private final Normalizer decimalText;
  private final Normalizer percentText;
  private final NumberValidator nonNegative;

  /** Resolves the fixed rules from the registry. */
  public JsonFieldReader(RuleRegistry registry) {
    this.decimalText = registry.normalizerFor(List.of("trim", "normalizeGroupedNumber"));
    this.percentText =
        registry.normalizerFor(List.of("trim", "stripTrailingPercent", "normalizeGroupedNumber"));
    this.nonNegative = registry.numberValidatorFor(List.of("nonNegative"));
  }

  /**
   * Reads the value found at {@code path}.
   *
   * @throws IllegalArgumentException if the type is one JSON contracts cannot use
   */
  public JsonCanonicalField read(String name, String path, FieldType type, SourceValue value) {
    Reading reading = new Reading(name, path, type, value);
    FieldPresence presence = FieldPresence.of(value);
    if (presence.isNoUpdate()) {
      return reading.passed(null, null);
    }
    return switch (type) {
      case TEXT -> text(reading);
      case DATE -> date(reading);
      case DECIMAL -> number(reading, decimalText);
      case PERCENT -> number(reading, percentText);
      case INTEGER ->
          throw new IllegalArgumentException("JSON field " + name + " cannot have type " + type);
    };
  }

  /**
   * Reports a field whose path cannot be reached because a value on the way has the wrong kind (LLD
   * section 13.7). The field is not treated as merely absent.
   */
  public static JsonCanonicalField unreachable(
      String name, String path, FieldType type, JsonMatch.WrongStructure structure) {
    return new Reading(name, path, type, new SourceValue.Missing())
        .failed(
            null,
            null,
            new ValidationIssue(
                ErrorCode.INVALID_TYPE,
                "Expected an "
                    + structure.expected()
                    + " at "
                    + structure.path()
                    + " but found "
                    + structure.actual()
                    + "."));
  }

  private static JsonCanonicalField text(Reading reading) {
    FieldResult<String> text = TextFieldReader.read(reading.value());
    if (text instanceof FieldResult.Rejected<String> rejected) {
      return reading.failed(null, null, issue(rejected));
    }
    String value = ((FieldResult.Valid<String>) text).value();
    return reading.passed(value, value);
  }

  private static JsonCanonicalField date(Reading reading) {
    if (!(reading.value() instanceof SourceValue.Text text)) {
      return reading.failed(null, null, wrongKind("a date string", reading.value()));
    }
    String normalized = text.value().strip();
    FieldResult<LocalDate> parsed = DateParser.parse(normalized);
    if (parsed instanceof FieldResult.Rejected<LocalDate> rejected) {
      return reading.failed(normalized, null, issue(rejected));
    }
    return reading.passed(normalized, ((FieldResult.Valid<LocalDate>) parsed).value().toString());
  }

  /** Reads a decimal or percent: a JSON number as is, or numeric text after normalization. */
  private JsonCanonicalField number(Reading reading, Normalizer textNormalizer) {
    @Nullable String normalized;
    FieldResult<BigDecimal> parsed;
    if (reading.value() instanceof SourceValue.Decimal decimal) {
      parsed = ExactNumbers.decimal(decimal.value());
      normalized = parsed instanceof FieldResult.Valid ? decimal.value().toPlainString() : null;
    } else if (reading.value() instanceof SourceValue.Text text) {
      FieldResult<String> cleaned = textNormalizer.normalize(text.value());
      if (cleaned instanceof FieldResult.Rejected<String> rejected) {
        return reading.failed(null, null, issue(rejected));
      }
      normalized = ((FieldResult.Valid<String>) cleaned).value();
      parsed = ExactNumbers.decimal(normalized);
    } else {
      return reading.failed(null, null, wrongKind("a number or numeric string", reading.value()));
    }
    if (parsed instanceof FieldResult.Rejected<BigDecimal> rejected) {
      return reading.failed(normalized, null, issue(rejected));
    }
    BigDecimal value = ((FieldResult.Valid<BigDecimal>) parsed).value();
    ValidationIssue[] errors =
        nonNegative.validate(value).stream()
            .map(violation -> new ValidationIssue(violation.code(), violation.message()))
            .toArray(ValidationIssue[]::new);
    return errors.length == 0
        ? reading.passed(normalized, value.toPlainString())
        : reading.failed(normalized, value.toPlainString(), errors);
  }

  private static ValidationIssue issue(FieldResult.Rejected<?> rejected) {
    return new ValidationIssue(rejected.code(), rejected.message());
  }

  private static ValidationIssue wrongKind(String expected, SourceValue actual) {
    return new ValidationIssue(
        ErrorCode.INVALID_TYPE, "Expected " + expected + " but found " + actual.kindName() + ".");
  }

  /** The field being read, which builds its canonical result. */
  private record Reading(String name, String path, FieldType type, SourceValue value) {

    JsonCanonicalField passed(@Nullable String normalized, @Nullable String parsed) {
      return new JsonCanonicalField(
          name,
          path,
          FieldPresence.of(value),
          value,
          normalized,
          parsed,
          type,
          ValidationStatus.PASSED,
          List.of());
    }

    JsonCanonicalField failed(
        @Nullable String normalized, @Nullable String parsed, ValidationIssue... errors) {
      return new JsonCanonicalField(
          name,
          path,
          FieldPresence.of(value),
          value,
          normalized,
          parsed,
          type,
          ValidationStatus.FAILED,
          List.of(errors));
    }
  }
}
