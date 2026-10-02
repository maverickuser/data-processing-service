package com.bondplatform.dataprocessing.contract.domain;

import java.util.Locale;

/** The text normalizers a contract can name. */
final class TextNormalizers {

  /** {@code trim}: removes surrounding whitespace. */
  static final Normalizer TRIM = text -> new FieldResult.Valid<>(text.strip());

  /** {@code blankToNull}: treats empty or whitespace-only text as no value. */
  static final Normalizer BLANK_TO_NULL =
      text ->
          text.isBlank()
              ? new FieldResult.NoValue<>(FieldPresence.PLACEHOLDER)
              : new FieldResult.Valid<>(text);

  /** {@code uppercase}: uppercases without regard to the default locale. */
  static final Normalizer UPPERCASE =
      text -> new FieldResult.Valid<>(text.toUpperCase(Locale.ROOT));

  private TextNormalizers() {}
}
