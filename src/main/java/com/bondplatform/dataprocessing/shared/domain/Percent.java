package com.bondplatform.dataprocessing.shared.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * An exact, non-negative quantity in percentage points: {@code 8.94} means 8.94 percent, not
 * 0.0894.
 *
 * <p>Two percentages are equal when they are numerically equal, so {@code 8.94} equals {@code
 * 8.940}. Values above 100 are allowed.
 */
public final class Percent {

  /** The unit label carried next to the value in stored and serialized forms. */
  public static final String UNIT = "PERCENT";

  private static final Pattern PLAIN_DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");

  private static final int MAX_DIGITS_EACH_SIDE_OF_THE_POINT = 30;

  private final BigDecimal value;

  private Percent(BigDecimal value) {
    this.value = value;
  }

  /**
   * Creates a percentage from an exact number.
   *
   * <p>The number may have at most 30 digits before and 30 after the decimal point. The bound has
   * no business meaning; it stops a value such as {@code 1e999999999}, which is tiny to write and
   * enormous to print, from being accepted.
   *
   * @throws IllegalArgumentException if the number is negative or outside that bound
   */
  public static Percent of(BigDecimal value) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("Percent must not be negative: " + value);
    }
    if (!hasAllowedDigits(value)) {
      throw new IllegalArgumentException("Percent has too many digits: " + value);
    }
    return new Percent(value);
  }

  /** Returns whether a number has at most 30 digits before and 30 after the decimal point. */
  public static boolean hasAllowedDigits(BigDecimal value) {
    long integerDigits = (long) value.precision() - value.scale();
    return integerDigits <= MAX_DIGITS_EACH_SIDE_OF_THE_POINT
        && value.scale() <= MAX_DIGITS_EACH_SIDE_OF_THE_POINT;
  }

  /**
   * Parses text such as {@code "8.94"}, {@code "8.94%"}, or {@code " 100 % "}.
   *
   * <p>The number must be plain ASCII digits with an optional fraction. Signs, scientific notation
   * ({@code 1e2}), a bare leading or trailing point, and non-ASCII digits are rejected.
   *
   * @throws IllegalArgumentException if the text is not such a number
   */
  public static Percent parse(String text) {
    String number = text.strip();
    if (number.endsWith("%")) {
      number = number.substring(0, number.length() - 1).strip();
    }
    if (!PLAIN_DECIMAL.matcher(number).matches()) {
      throw new IllegalArgumentException("Percent must be a plain decimal number: " + text);
    }
    return of(new BigDecimal(number));
  }

  /** Returns the exact value in percentage points, with the scale it was created with. */
  public BigDecimal value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Percent that && value.compareTo(that.value) == 0;
  }

  @Override
  public int hashCode() {
    return value.stripTrailingZeros().hashCode();
  }

  @Override
  public String toString() {
    return value.toPlainString() + " " + UNIT;
  }
}
