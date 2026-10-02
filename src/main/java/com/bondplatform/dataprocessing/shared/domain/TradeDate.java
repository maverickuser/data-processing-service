package com.bondplatform.dataprocessing.shared.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * The trading day a daily market summary describes.
 *
 * @param value the calendar date
 */
public record TradeDate(LocalDate value) {

  private static final DateTimeFormatter ISO_STRICT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  /**
   * Parses an ISO {@code YYYY-MM-DD} date strictly: an impossible date such as {@code 2026-02-30}
   * is rejected rather than adjusted.
   *
   * @throws IllegalArgumentException if the text is not a real calendar date in that format
   */
  public static TradeDate parseIso(String text) {
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
