package com.bondplatform.dataprocessing.contract.domain;

import java.util.Set;

/**
 * Whether a selected JSON field carries a value to act on (LLD section 13.4).
 *
 * <p>Only {@link #PRESENT} can update business data. The other three are all "no update" and are
 * never errors; they are kept apart only so the canonical record can say which one occurred.
 */
public enum FieldPresence {
  /** The path does not exist. */
  MISSING,
  /** The path holds an explicit {@code null}. */
  NULL,
  /** The path holds blank text or a source placeholder such as {@code -} or {@code N.A.}. */
  PLACEHOLDER,
  /** The path holds something that must be read as a value. */
  PRESENT;

  private static final Set<String> PLACEHOLDERS = Set.of("-", "N.A.");

  /** Classifies a source value. Surrounding whitespace is ignored when recognizing placeholders. */
  public static FieldPresence of(SourceValue value) {
    return switch (value) {
      case SourceValue.Missing ignored -> MISSING;
      case SourceValue.Null ignored -> NULL;
      case SourceValue.Text text -> isPlaceholder(text.value()) ? PLACEHOLDER : PRESENT;
      default -> PRESENT;
    };
  }

  /** Returns whether a field with this presence leaves existing data unchanged. */
  public boolean isNoUpdate() {
    return this != PRESENT;
  }

  private static boolean isPlaceholder(String text) {
    String stripped = text.strip();
    return stripped.isEmpty() || PLACEHOLDERS.contains(stripped);
  }
}
