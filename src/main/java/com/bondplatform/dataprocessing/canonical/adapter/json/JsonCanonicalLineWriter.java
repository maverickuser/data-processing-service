package com.bondplatform.dataprocessing.canonical.adapter.json;

import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.JsonCollectionEntry;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileEvidence;
import com.bondplatform.dataprocessing.canonical.domain.JsonRejection;
import com.bondplatform.dataprocessing.canonical.domain.StructureIssue;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a JSON source file's evidence as one line of the run's canonical file, and a rejected part
 * as the record kept for review (LLD section 17.5).
 *
 * <p>A value as read keeps its JSON type: a string, an exact number, a boolean, or {@code null}. A
 * missing value has none, and an object or array keeps only its kind, given as {@code rawKind}.
 * Parsed values are strings with an explicit {@code dataType}, as in CSV lines. Business rows
 * reference a line by job, source file and JSONPath.
 */
public final class JsonCanonicalLineWriter {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory NODES = JSON.getNodeFactory();

  private JsonCanonicalLineWriter() {}

  /** Returns the file's evidence as JSON text without a line end. */
  public static String line(JsonCanonicalRun run, JsonFileEvidence file) {
    ObjectNode line = JSON.createObjectNode();
    line.put("jobId", run.jobId().value().toString());
    line.put("attemptNumber", run.attemptNumber());
    line.put("isin", run.isin().value());
    line.putObject("source").put("bucket", file.bucket()).put("key", file.key());
    line.put("sourceContractVersion", run.sourceContractVersion());
    line.put("mappingContractVersion", run.mappingContractVersion());
    switch (file.canonical()) {
      case JsonFileCanonical.Skipped skipped -> {
        line.put("status", "SKIPPED");
        issue(line.putArray("fileErrors").addObject(), skipped.issue());
      }
      case JsonFileCanonical.Read read -> {
        line.put("status", "READ");
        ArrayNode scalars = line.putArray("scalars");
        read.scalars().forEach(field -> field(scalars.addObject(), field));
        ArrayNode entries = line.putArray("entries");
        read.entries().forEach(entry -> entry(entries.addObject(), entry));
        ArrayNode sections = line.putArray("structureIssues");
        read.structureIssues().forEach(issue -> structure(sections.addObject(), issue));
        ArrayNode warnings = line.putArray("warnings");
        read.warnings().forEach(warnings::add);
        ArrayNode ignored = line.putArray("ignoredValues");
        file.ignored().forEach(value -> ignored(ignored.addObject(), value));
      }
    }
    return JSON.writeValueAsString(line);
  }

  /** Returns the evidence of one rejected part, as kept in {@code rejected_records.record}. */
  public static String record(JsonRejection rejection) {
    ObjectNode record = JSON.createObjectNode();
    switch (rejection) {
      case JsonRejection.FileSkipped skipped -> issue(record, skipped.issue());
      case JsonRejection.SectionRejected section -> structure(record, section.structure());
      case JsonRejection.FieldRejected field -> field(record, field.field());
      case JsonRejection.EntryRejected entry -> entry(record, entry.entry());
      case JsonRejection.ValueIgnored value -> ignored(record, value);
    }
    return JSON.writeValueAsString(record);
  }

  /**
   * Returns a value as read as JSON text, or empty when it has none to show: a missing value, or an
   * object or array, whose structure is not kept.
   */
  public static Optional<String> rawValue(SourceValue value) {
    return json(value).map(JSON::writeValueAsString);
  }

  private static void field(ObjectNode node, JsonCanonicalField field) {
    node.put("name", field.name());
    node.put("path", field.path());
    node.put("presence", field.presence().name());
    raw(node, field.rawValue());
    node.put("normalizedValue", field.normalizedValue());
    node.put("parsedValue", field.parsedValue());
    node.put("dataType", field.dataType().name());
    node.put("validationStatus", field.validationStatus().name());
    ArrayNode errors = node.putArray("errors");
    field.errors().forEach(error -> issue(errors.addObject(), error));
  }

  private static void entry(ObjectNode node, JsonCollectionEntry entry) {
    node.put("collection", entry.collection());
    node.put("path", entry.path());
    node.put("disposition", entry.disposition().name());
    ArrayNode fields = node.putArray("fields");
    entry.fields().forEach(field -> field(fields.addObject(), field));
  }

  private static void structure(ObjectNode node, StructureIssue structure) {
    node.put("path", structure.path());
    issue(node, structure.issue());
  }

  private static void ignored(ObjectNode node, JsonRejection.ValueIgnored value) {
    node.put("path", value.path());
    raw(node, value.rawValue());
    issue(node, value.issue());
    node.put("actionTaken", value.actionTaken());
  }

  private static void issue(ObjectNode node, ValidationIssue issue) {
    node.put("code", issue.code().name()).put("message", issue.message());
  }

  private static void raw(ObjectNode node, SourceValue value) {
    node.put("rawKind", value.kindName());
    json(value).ifPresent(json -> node.set("rawValue", json));
  }

  private static Optional<JsonNode> json(SourceValue value) {
    return switch (value) {
      case SourceValue.Text text -> Optional.of(NODES.stringNode(text.value()));
      case SourceValue.Decimal decimal -> Optional.of(NODES.numberNode(decimal.value()));
      case SourceValue.Bool bool -> Optional.of(NODES.booleanNode(bool.value()));
      case SourceValue.Null ignored -> Optional.of(NODES.nullNode());
      case SourceValue.Missing ignored -> Optional.empty();
      case SourceValue.Structured ignored -> Optional.empty();
    };
  }
}
