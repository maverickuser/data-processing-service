package com.bondplatform.dataprocessing.source.domain;

import java.util.Locale;
import java.util.Optional;

/** The format of a listed source file, as the manifest names it. */
public enum SourceFormat {
  CSV,
  JSON;

  /** Returns the format a manifest names, in any case, or empty if it is not one of these. */
  public static Optional<SourceFormat> of(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "csv" -> Optional.of(CSV);
      case "json" -> Optional.of(JSON);
      default -> Optional.empty();
    };
  }
}
