package com.bondplatform.dataprocessing.contract.domain;

/**
 * One text normalization step named in a contract, such as {@code trim}.
 *
 * <p>A normalizer never changes the stored raw value; it produces the text the next step sees. A
 * step may also decide that the field has no value, or reject text it cannot normalize safely.
 */
@FunctionalInterface
public interface Normalizer {

  /**
   * Normalizes the output of the previous step.
   *
   * @return the normalized text, {@link FieldResult.NoValue} when the field turns out to be empty,
   *     or {@link FieldResult.Rejected} when the text is malformed
   */
  FieldResult<String> normalize(String text);

  /**
   * Returns a normalizer that applies this one and then {@code next}. Later steps run only while
   * the field still has valid text.
   */
  default Normalizer then(Normalizer next) {
    return text -> {
      FieldResult<String> result = normalize(text);
      return result instanceof FieldResult.Valid<String> valid
          ? next.normalize(valid.value())
          : result;
    };
  }
}
