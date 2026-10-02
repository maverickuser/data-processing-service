package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Parses normalized number text exactly: no floating point, no rounding, every digit kept.
 *
 * <p>The input is the output of {@code normalizeGroupedNumber}: digits with an optional leading
 * minus and an optional fraction.
 */
public final class ExactNumbers {

  static final String DECIMAL_MESSAGE =
      "Expected a decimal with a dot decimal separator and optional valid comma grouping.";

  private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

  private ExactNumbers() {}

  /** Parses a decimal, keeping its scale: {@code "1234.5600"} stays {@code 1234.5600}. */
  public static FieldResult<BigDecimal> decimal(String text) {
    if (!PLAIN_DECIMAL.matcher(text).matches()) {
      return new FieldResult.Rejected<>(ErrorCode.INVALID_DECIMAL, DECIMAL_MESSAGE);
    }
    return new FieldResult.Valid<>(new BigDecimal(text));
  }

  /**
   * Parses a mathematical whole number: {@code "14"} and {@code "14.00"} are both 14, and {@code
   * "14.50"} is rejected rather than truncated.
   */
  public static FieldResult<BigInteger> wholeNumber(String text) {
    if (!(decimal(text) instanceof FieldResult.Valid<BigDecimal> parsed)) {
      return new FieldResult.Rejected<>(ErrorCode.INVALID_DECIMAL, DECIMAL_MESSAGE);
    }
    BigDecimal value = parsed.value();
    if (value.stripTrailingZeros().scale() > 0) {
      return new FieldResult.Rejected<>(
          ErrorCode.NOT_WHOLE_NUMBER, "Expected a whole number without a fraction.");
    }
    return new FieldResult.Valid<>(value.toBigIntegerExact());
  }
}
