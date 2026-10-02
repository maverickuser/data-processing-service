package com.bondplatform.dataprocessing.contract.domain;

import java.math.BigDecimal;

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

  /** The path holds an object or an array. */
  record Structured(Kind kind) implements SourceValue {

    /** The two structured JSON kinds. */
    public enum Kind {
      OBJECT,
      ARRAY
    }
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
