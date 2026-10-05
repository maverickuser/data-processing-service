package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A part of a JSON source file that was not used as supplied, kept for the error-review API: a
 * skipped file, a section of the wrong kind, a failed field, an entry with failed fields, or a
 * value ignored by a stage-2 rule (LLD sections 13.7, 16 and 17.5).
 */
public sealed interface JsonRejection {

  /** How a rejected part was handled. */
  enum Disposition {
    /** The whole file was skipped, such as a malformed file. */
    FILE_SKIPPED,
    /** A section had the wrong kind of value, and the fields under it were not read. */
    SECTION_REJECTED,
    /** A scalar failed validation and updates nothing. */
    FIELD_REJECTED,
    /** A collection entry was appended with its valid fields only. */
    ENTRY_FIELDS_REJECTED,
    /** A collection entry had no valid field and was skipped. */
    ENTRY_SKIPPED,
    /** A valid value was ignored by a stage-2 rule. */
    VALUE_IGNORED
  }

  /** Returns the JSONPath of the rejected part; {@code $} for a whole file. */
  String path();

  /** Returns how the part was handled. */
  Disposition disposition();

  /** Returns the part's reviewable issues, in the part's order; never empty. */
  List<Issue> issues();

  /**
   * One reviewable issue, before it is numbered in the run.
   *
   * @param field the source field the issue is about, or {@code null} for a file or section
   * @param path the JSONPath the issue is about
   * @param rawValue the value as read; missing or structured values are not shown in the API
   * @param issue the code and message
   * @param actionTaken what the service did with the value, or {@code null} when nothing beyond
   *     rejecting it
   */
  record Issue(
      @Nullable String field,
      String path,
      SourceValue rawValue,
      ValidationIssue issue,
      @Nullable String actionTaken) {}

  /** A file skipped as a whole. */
  record FileSkipped(ValidationIssue issue) implements JsonRejection {

    @Override
    public String path() {
      return "$";
    }

    @Override
    public Disposition disposition() {
      return Disposition.FILE_SKIPPED;
    }

    @Override
    public List<Issue> issues() {
      return List.of(new Issue(null, path(), new SourceValue.Missing(), issue, null));
    }
  }

  /** A section of the wrong kind. */
  record SectionRejected(StructureIssue structure) implements JsonRejection {

    @Override
    public String path() {
      return structure.path();
    }

    @Override
    public Disposition disposition() {
      return Disposition.SECTION_REJECTED;
    }

    @Override
    public List<Issue> issues() {
      return List.of(new Issue(null, path(), new SourceValue.Missing(), structure.issue(), null));
    }
  }

  /** A scalar that failed validation with errors of its own. */
  record FieldRejected(JsonCanonicalField field) implements JsonRejection {

    /** Checks that the field has errors. */
    public FieldRejected {
      if (field.errors().isEmpty()) {
        throw new IllegalArgumentException("Field " + field.path() + " has no errors");
      }
    }

    @Override
    public String path() {
      return field.path();
    }

    @Override
    public Disposition disposition() {
      return Disposition.FIELD_REJECTED;
    }

    @Override
    public List<Issue> issues() {
      return issuesOf(field);
    }
  }

  /** A collection entry with at least one field error. */
  record EntryRejected(JsonCollectionEntry entry) implements JsonRejection {

    /** Checks that a field of the entry has errors. */
    public EntryRejected {
      if (entry.fields().stream().allMatch(field -> field.errors().isEmpty())) {
        throw new IllegalArgumentException("Entry " + entry.path() + " has no errors");
      }
    }

    @Override
    public String path() {
      return entry.path();
    }

    @Override
    public Disposition disposition() {
      return entry.disposition() == EntryDisposition.ACCEPTED
          ? Disposition.ENTRY_FIELDS_REJECTED
          : Disposition.ENTRY_SKIPPED;
    }

    @Override
    public List<Issue> issues() {
      return entry.fields().stream().flatMap(field -> issuesOf(field).stream()).toList();
    }
  }

  /**
   * A valid value ignored by a stage-2 rule, such as coverage supplied with an Unsecured status.
   *
   * @param actionTaken what the service did instead
   */
  record ValueIgnored(String path, SourceValue rawValue, ValidationIssue issue, String actionTaken)
      implements JsonRejection {

    @Override
    public Disposition disposition() {
      return Disposition.VALUE_IGNORED;
    }

    @Override
    public List<Issue> issues() {
      return List.of(new Issue(null, path, rawValue, issue, actionTaken));
    }
  }

  private static List<Issue> issuesOf(JsonCanonicalField field) {
    return field.errors().stream()
        .map(error -> new Issue(field.name(), field.path(), field.rawValue(), error, null))
        .toList();
  }
}
