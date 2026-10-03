package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.Locale;
import java.util.Optional;

/**
 * The security an NSDL file name identifies: the text before the first underscore, or before {@code
 * .json} when there is no underscore, trimmed and uppercased (LLD section 13.1). Both {@code
 * INE831R08076_ratings.json} and {@code INE831R08076.json} name {@code INE831R08076}. No ISIN
 * format or check-digit rule applies.
 */
public final class JsonFilename {

  private static final String EXTENSION = ".json";

  private JsonFilename() {}

  /** Returns the ISIN the name identifies, or empty if it identifies none. */
  public static Optional<Isin> isinOf(String fileName) {
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
      return Optional.empty();
    }
    String stem = fileName.substring(0, fileName.length() - EXTENSION.length());
    int underscore = stem.indexOf('_');
    String identifier = underscore < 0 ? stem : stem.substring(0, underscore);
    return identifier.isBlank() ? Optional.empty() : Optional.of(Isin.of(identifier));
  }
}
