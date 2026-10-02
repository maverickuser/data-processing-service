package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the two date formats the sources use, {@code DD-MM-YYYY} and {@code YYYY-MM-DD} (LLD
 * section 13.4).
 *
 * <p>Parsing is strict: the text must have exactly that shape and name a real calendar date. An
 * impossible date such as {@code 30-02-2026} is rejected, never adjusted.
 */
public final class DateParser {

  private static final List<Format> FORMATS =
      List.of(
          new Format("[0-9]{2}-[0-9]{2}-[0-9]{4}", "dd-MM-uuuu"),
          new Format("[0-9]{4}-[0-9]{2}-[0-9]{2}", "uuuu-MM-dd"));

  private DateParser() {}

  /** Parses already-trimmed text into a date, or rejects it with {@code INVALID_DATE}. */
  public static FieldResult<LocalDate> parse(String text) {
    for (Format format : FORMATS) {
      if (format.shape.matcher(text).matches()) {
        try {
          return new FieldResult.Valid<>(LocalDate.parse(text, format.formatter));
        } catch (DateTimeParseException e) {
          break;
        }
      }
    }
    return new FieldResult.Rejected<>(
        ErrorCode.INVALID_DATE, "Expected a real date as DD-MM-YYYY or YYYY-MM-DD.");
  }

  private static final class Format {
    private final Pattern shape;
    private final DateTimeFormatter formatter;

    Format(String shape, String pattern) {
      this.shape = Pattern.compile(shape);
      this.formatter = DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }
  }
}
