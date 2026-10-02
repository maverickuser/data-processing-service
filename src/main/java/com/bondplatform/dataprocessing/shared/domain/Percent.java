package com.bondplatform.dataprocessing.shared.domain;

import java.math.BigDecimal;

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

  private final BigDecimal value;

  private Percent(BigDecimal value) {
    this.value = value;
  }

  /**
   * Creates a percentage from an exact number.
   *
   * @throws IllegalArgumentException if the number is negative
   */
  public static Percent of(BigDecimal value) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("Percent must not be negative: " + value.toPlainString());
    }
    return new Percent(value);
  }

  /**
   * Parses text such as {@code "8.94"}, {@code "8.94%"}, or {@code " 100 % "}.
   *
   * @throws IllegalArgumentException if the text is not a non-negative decimal number
   */
  public static Percent parse(String text) {
    String number = text.strip();
    if (number.endsWith("%")) {
      number = number.substring(0, number.length() - 1).strip();
    }
    try {
      return of(new BigDecimal(number));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Percent must be a decimal number: " + text, e);
    }
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
