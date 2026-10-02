package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.regex.Pattern;

/** The number normalizers a contract can name. */
final class NumberNormalizers {

  private static final String SIGN = "-?";
  private static final String FRACTION = "(\\.[0-9]+)?";
  private static final Pattern UNGROUPED = Pattern.compile(SIGN + "[0-9]+" + FRACTION);
  private static final Pattern WESTERN_GROUPING =
      Pattern.compile(SIGN + "[0-9]{1,3}(,[0-9]{3})+" + FRACTION);
  private static final Pattern INDIAN_GROUPING =
      Pattern.compile(SIGN + "[0-9]{1,2}(,[0-9]{2})*,[0-9]{3}" + FRACTION);

  /**
   * {@code normalizeGroupedNumber}: accepts an ungrouped number or one with valid Western ({@code
   * 1,234,567.89}) or Indian ({@code 12,34,567.89}) comma grouping and a dot as the decimal
   * separator, and returns it without commas.
   *
   * <p>Grouping is validated before the commas are removed; malformed separators are never stripped
   * blindly. Scientific notation, currency symbols, a plus sign, and parentheses for negatives are
   * rejected. A leading minus is kept so that a negative number can be parsed and then reported as
   * negative rather than as unparseable.
   */
  static final Normalizer GROUPED_NUMBER =
      text -> {
        if (UNGROUPED.matcher(text).matches()) {
          return new FieldResult.Valid<>(text);
        }
        if (WESTERN_GROUPING.matcher(text).matches() || INDIAN_GROUPING.matcher(text).matches()) {
          return new FieldResult.Valid<>(text.replace(",", ""));
        }
        return text.contains(",") && looksNumeric(text)
            ? new FieldResult.Rejected<>(
                ErrorCode.INVALID_NUMBER_GROUPING,
                "Expected Western (1,234,567) or Indian (12,34,567) comma grouping.")
            : new FieldResult.Rejected<>(ErrorCode.INVALID_DECIMAL, ExactNumbers.DECIMAL_MESSAGE);
      };

  /** {@code stripTrailingPercent}: removes one trailing percent sign and the space before it. */
  static final Normalizer STRIP_TRAILING_PERCENT =
      text ->
          new FieldResult.Valid<>(
              text.endsWith("%") ? text.substring(0, text.length() - 1).strip() : text);

  private static final Pattern DIGITS_AND_SEPARATORS = Pattern.compile(SIGN + "[0-9.,]+");

  private NumberNormalizers() {}

  /** Tells badly grouped digits apart from text that is not a number at all. */
  private static boolean looksNumeric(String text) {
    return DIGITS_AND_SEPARATORS.matcher(text).matches();
  }
}
