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

  /**
   * The longest number text accepted. Far beyond any real price or amount, and small enough that
   * exact parsing, whose cost grows with the square of the length, stays instant.
   */
  static final int MAX_LENGTH = 1_000;

  private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

  private ExactNumbers() {}

  /** Parses a decimal, keeping its scale: {@code "1234.5600"} stays {@code 1234.5600}. */
  public static FieldResult<BigDecimal> decimal(String text) {
    if (text.length() > MAX_LENGTH) {
      return new FieldResult.Rejected<>(
          ErrorCode.INVALID_DECIMAL, "Expected a number of at most " + MAX_LENGTH + " characters.");
    }
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
    FieldResult<BigDecimal> decimal = decimal(text);
    if (decimal instanceof FieldResult.Rejected<BigDecimal> rejected) {
      return new FieldResult.Rejected<>(rejected.code(), rejected.message());
    }
    BigDecimal value = ((FieldResult.Valid<BigDecimal>) decimal).value();
    if (value.stripTrailingZeros().scale() > 0) {
      return new FieldResult.Rejected<>(
          ErrorCode.NOT_WHOLE_NUMBER, "Expected a whole number without a fraction.");
    }
    return new FieldResult.Valid<>(value.toBigIntegerExact());
  }
}
