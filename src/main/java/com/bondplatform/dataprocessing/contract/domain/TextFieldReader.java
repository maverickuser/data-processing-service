package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * Reads a selected JSON text field (LLD section 13.4).
 *
 * <p>Only a JSON string is accepted. A number, boolean, object, or array is rejected rather than
 * converted to text. Surrounding whitespace is removed and letter case is preserved.
 */
public final class TextFieldReader {

  private TextFieldReader() {}

  /** Reads the value as text, reports that it has no value, or rejects its kind. */
  public static FieldResult<String> read(SourceValue value) {
    FieldPresence presence = FieldPresence.of(value);
    if (presence.isNoUpdate()) {
      return new FieldResult.NoValue<>(presence);
    }
    if (value instanceof SourceValue.Text text) {
      return new FieldResult.Valid<>(text.value().strip());
    }
    return new FieldResult.Rejected<>(
        ErrorCode.INVALID_TYPE, "Expected a string but found " + value.kindName() + ".");
  }
}
