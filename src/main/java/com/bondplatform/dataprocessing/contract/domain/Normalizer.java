package com.bondplatform.dataprocessing.contract.domain;

import org.jspecify.annotations.Nullable;

/**
 * One text normalization step named in a contract, such as {@code trim}.
 *
 * <p>A normalizer never changes the stored raw value; it produces the value the next step sees.
 */
@FunctionalInterface
public interface Normalizer {

  /**
   * Returns the normalized text, or {@code null} when the step decides the field has no value.
   *
   * @param text the output of the previous step, never null
   */
  @Nullable String normalize(String text);

  /**
   * Returns a normalizer that applies this one and then {@code next}. Once a step yields no value,
   * later steps are skipped.
   */
  default Normalizer then(Normalizer next) {
    return text -> {
      String normalized = normalize(text);
      return normalized == null ? null : next.normalize(normalized);
    };
  }
}
