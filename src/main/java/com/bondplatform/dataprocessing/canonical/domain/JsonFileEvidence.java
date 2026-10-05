package com.bondplatform.dataprocessing.canonical.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What one JSON source file contributed to a run: its stage-1 result and the valid values stage 2
 * ignored. It is one line of the run's canonical file, and its rejections are what the error-review
 * API shows for the file (LLD sections 16 and 17.5).
 *
 * @param bucket the source file's bucket
 * @param key the source file's object key
 * @param canonical the file's stage-1 result
 * @param ignored valid values of the file that stage 2 ignored, in file order
 */
public record JsonFileEvidence(
    String bucket,
    String key,
    JsonFileCanonical canonical,
    List<JsonRejection.ValueIgnored> ignored) {

  /**
   * Copies the list.
   *
   * @throws IllegalArgumentException if a skipped file has ignored values
   */
  public JsonFileEvidence {
    ignored = List.copyOf(ignored);
    if (canonical instanceof JsonFileCanonical.Skipped && !ignored.isEmpty()) {
      throw new IllegalArgumentException("A skipped file has no values to ignore: " + key);
    }
  }

  /** Returns the object key's last segment, the only part of the location shown publicly. */
  public String fileName() {
    return key.substring(key.lastIndexOf('/') + 1);
  }

  /**
   * Returns the file's rejected parts in review order: the skipped file, or else sections of the
   * wrong kind, failed scalars, entries with failed fields, then ignored values.
   */
  public List<JsonRejection> rejections() {
    return switch (canonical) {
      case JsonFileCanonical.Skipped skipped ->
          List.of(new JsonRejection.FileSkipped(skipped.issue()));
      case JsonFileCanonical.Read read -> {
        List<JsonRejection> rejections = new ArrayList<>();
        read.structureIssues()
            .forEach(issue -> rejections.add(new JsonRejection.SectionRejected(issue)));
        read.scalars().stream()
            .filter(field -> !field.errors().isEmpty())
            .forEach(field -> rejections.add(new JsonRejection.FieldRejected(field)));
        read.entries().stream()
            .filter(entry -> entry.fields().stream().anyMatch(field -> !field.errors().isEmpty()))
            .forEach(entry -> rejections.add(new JsonRejection.EntryRejected(entry)));
        rejections.addAll(ignored);
        yield List.copyOf(rejections);
      }
    };
  }

  /**
   * Returns the number of selected fields that failed validation, in scalars and entries alike,
   * including those not read because their section had the wrong kind.
   */
  public int rejectedFieldCount() {
    return switch (canonical) {
      case JsonFileCanonical.Skipped ignoredFile -> 0;
      case JsonFileCanonical.Read read ->
          Math.toIntExact(
              Stream.concat(
                      read.scalars().stream(),
                      read.entries().stream().flatMap(entry -> entry.fields().stream()))
                  .filter(field -> field.validationStatus() == ValidationStatus.FAILED)
                  .count());
    };
  }
}
