package com.bondplatform.dataprocessing.shared.domain;

import java.util.Locale;

/** Text normalization shared by the identifier value types. */
final class NormalizedText {

  private NormalizedText() {}

  /** Removes surrounding whitespace and uppercases without regard to the default locale. */
  static String trimmedUppercase(String rawValue) {
    return rawValue.strip().toUpperCase(Locale.ROOT);
  }
}
