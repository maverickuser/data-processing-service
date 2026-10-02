package com.bondplatform.dataprocessing.shared.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The trading day a daily market summary describes.
 *
 * @param value the calendar date
 */
public record TradeDate(LocalDate value) {

  private static final DateTimeFormatter ISO_STRICT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern FOUR_DIGIT_YEAR_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

  /** Rejects a missing date. */
  public TradeDate {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Parses an ISO {@code YYYY-MM-DD} date strictly: exactly four year digits, no sign, and an
   * impossible date such as {@code 2026-02-30} is rejected rather than adjusted.
   *
   * @throws IllegalArgumentException if the text is not a real calendar date in that format
   */
  public static TradeDate parseIso(String text) {
    if (!FOUR_DIGIT_YEAR_DATE.matcher(text).matches()) {
      throw new IllegalArgumentException("Trade date must be a valid YYYY-MM-DD date: " + text);
    }
    try {
      return new TradeDate(LocalDate.parse(text, ISO_STRICT));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Trade date must be a valid YYYY-MM-DD date: " + text, e);
    }
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
