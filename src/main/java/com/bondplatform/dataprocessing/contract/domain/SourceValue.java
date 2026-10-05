package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.CanonicalJson;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * What a source document holds at a selected path, before any normalization.
 *
 * <p>The kinds mirror JSON so that a wrong kind can be reported rather than coerced. A CSV cell is
 * always {@link Text}.
 */
public sealed interface SourceValue {

  /** The path does not exist in the document. */
  record Missing() implements SourceValue {}

  /** The path holds an explicit JSON {@code null}. */
  record Null() implements SourceValue {}

  /** The path holds text. */
  record Text(String value) implements SourceValue {}

  /** The path holds a number, kept exactly. */
  record Decimal(BigDecimal value) implements SourceValue {}

  /** The path holds {@code true} or {@code false}. */
  record Bool(boolean value) implements SourceValue {}

  /**
   * The path holds an object or an array.
   *
   * @param kind which of the two it is
   * @param json the value as compact JSON text, object properties in document order, kept as
   *     evidence (LLD section 16)
   */
  record Structured(Kind kind, String json) implements SourceValue {

    /** Rejects missing text. */
    public Structured {
      Objects.requireNonNull(json, "json");
    }

    /** The two structured JSON kinds. */
    public enum Kind {
      OBJECT,
      ARRAY
    }
  }

  /**
   * Returns the value as compact JSON text, keeping its type and a number's scale, or empty when it
   * is missing.
   */
  default Optional<String> toJson() {
    return switch (this) {
      case Missing ignored -> Optional.empty();
      case Null ignored -> Optional.of("null");
      case Text text -> Optional.of(CanonicalJson.of(text.value()));
      case Decimal decimal -> Optional.of(decimal.value().toString());
      case Bool bool -> Optional.of(Boolean.toString(bool.value()));
      case Structured structured -> Optional.of(structured.json());
    };
  }

  /** Returns the JSON name of this value's kind, for error messages. */
  default String kindName() {
    return switch (this) {
      case Missing ignored -> "missing";
      case Null ignored -> "null";
      case Text ignored -> "string";
      case Decimal ignored -> "number";
      case Bool ignored -> "boolean";
      case Structured structured ->
          structured.kind() == Structured.Kind.OBJECT ? "object" : "array";
    };
  }
}
