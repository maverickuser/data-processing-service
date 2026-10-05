package com.bondplatform.dataprocessing.canonical.domain;

import java.util.List;

/** Stage 1's result for one JSON source file (LLD section 13.7). */
public sealed interface JsonFileCanonical {

  /**
   * The file is skipped with one error and contributes nothing; the request's other files continue.
   *
   * @param issue {@code MALFORMED_JSON}, saying where the file is malformed
   */
  record Skipped(ValidationIssue issue) implements JsonFileCanonical {}

  /**
   * The file was read.
   *
   * @param scalars one per selected scalar, in contract order
   * @param entries every entry of every selected collection, accepted and skipped
   * @param structureIssues one per section whose value had the wrong kind
   * @param warnings things worth logging that are not errors, such as a payload ISIN that differs
   *     from the file name's
   */
  record Read(
      List<JsonCanonicalField> scalars,
      List<JsonCollectionEntry> entries,
      List<StructureIssue> structureIssues,
      List<String> warnings)
      implements JsonFileCanonical {

    /** Copies the lists. */
    public Read {
      scalars = List.copyOf(scalars);
      entries = List.copyOf(entries);
      structureIssues = List.copyOf(structureIssues);
      warnings = List.copyOf(warnings);
    }

    /** Returns whether the file holds any valid selected value: a scalar or an accepted entry. */
    public boolean hasUsableData() {
      return scalars.stream().anyMatch(JsonCanonicalField::isUsable)
          || entries.stream().anyMatch(entry -> entry.disposition() == EntryDisposition.ACCEPTED);
    }
  }
}
