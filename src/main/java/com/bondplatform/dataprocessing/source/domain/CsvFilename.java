package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a bhavcopy's file name says: {@code {exchangeName}_...{DDMMYYYY}.csv}, for example {@code
 * BSE_fgroup01012026.csv} for BSE on 1 January 2026 (LLD section 2.1).
 *
 * <p>The exchange is the text before the first underscore; the date is the eight digits right
 * before {@code .csv}.
 */
public record CsvFilename(ExchangeName exchange, TradeDate tradeDate) {

  private static final Pattern NAME = Pattern.compile("([^_]*)_.*?([0-9]{8})\\.csv");

  /** Returns what the name says, or empty if it is not a valid bhavcopy file name. */
  public static Optional<CsvFilename> parse(String fileName) {
    Matcher matcher = NAME.matcher(fileName.toLowerCase(Locale.ROOT));
    if (!matcher.matches() || matcher.group(1).isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new CsvFilename(
              ExchangeName.of(fileName.substring(0, matcher.end(1))),
              TradeDate.parseDayMonthYear(matcher.group(2))));
    } catch (IllegalArgumentException e) {
      // An impossible calendar date: the name does not identify a trading day.
      return Optional.empty();
    }
  }
}
