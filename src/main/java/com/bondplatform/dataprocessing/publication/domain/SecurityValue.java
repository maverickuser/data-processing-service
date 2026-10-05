package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Percent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The typed value of one security field (LLD section 15.3).
 *
 * <p>Two values are equal when they mean the same thing, so a stored value is only replaced by one
 * that differs (LLD section 13.5): {@code 89400} equals {@code 89400.00}, and {@code 8.94} percent
 * equals {@code 8.940} percent.
 */
public sealed interface SecurityValue {

  /**
   * Text, kept exactly as normalized.
   *
   * @param value the text
   */
  record Text(String value) implements SecurityValue {

    /** Rejects a missing value. */
    public Text {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A calendar date.
   *
   * @param value the date
   */
  record Date(LocalDate value) implements SecurityValue {

    /** Rejects a missing value. */
    public Date {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * An exact decimal, equal to any other with the same numeric value.
   *
   * @param value the number, with the scale it was read with
   */
  record Decimal(BigDecimal value) implements SecurityValue {

    /** Rejects a missing value. */
    public Decimal {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Decimal that && value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
      return value.stripTrailingZeros().hashCode();
    }
  }

  /**
   * A percentage, stored as a value and the unit {@code PERCENT}.
   *
   * @param value the percentage
   */
  record Percentage(Percent value) implements SecurityValue {

    /** Rejects a missing value. */
    public Percentage {
      Objects.requireNonNull(value, "value");
    }
  }
}
