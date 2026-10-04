package com.bondplatform.dataprocessing.canonical.adapter.json;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRecord;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.CanonicalRun;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a canonical row as one line of JSON (LLD section 4.2).
 *
 * <p>Parsed numbers are JSON strings with an explicit {@code dataType}, so no reader loses
 * precision. A line is identified by its job, attempt, source object, and record number, which is
 * the origin reference business rows carry (LLD section 17.5).
 */
public final class CanonicalLineWriter {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CanonicalLineWriter() {}

  /** Returns the row as JSON text without a line end. */
  public static String line(CanonicalRun run, CanonicalRow row) {
    final CanonicalRecord record = row.record();
    ObjectNode line = JSON.createObjectNode();
    line.put("jobId", run.jobId().value().toString());
    line.put("attemptNumber", run.attemptNumber());
    line.put("tradeDate", run.tradeDate().toString());
    ObjectNode source = line.putObject("source");
    source.put("bucket", run.sourceBucket());
    source.put("key", run.sourceKey());
    source.put("recordNumber", record.recordNumber());
    line.put("sourceContractVersion", run.sourceContractVersion());
    line.put("mappingContractVersion", run.mappingContractVersion());
    ObjectNode fields = line.putObject("fields");
    record.fields().forEach((name, field) -> field(fields.putObject(name), field));
    line.put("rowValidationStatus", record.validationStatus().name());
    line.put("disposition", row.disposition().name());
    if (row.supersededBy().isPresent()) {
      line.put("supersededBy", row.supersededBy().getAsLong());
    } else {
      line.putNull("supersededBy");
    }
    issues(line.putArray("rowErrors"), row.rowErrors());
    return JSON.writeValueAsString(line);
  }

  private static void field(ObjectNode node, CanonicalField field) {
    node.put("sourceHeader", field.sourceHeader());
    node.put("columnIndex", field.columnIndex());
    node.put("rawValue", field.rawValue());
    node.put("normalizedValue", field.normalizedValue());
    node.put("parsedValue", field.parsedValue());
    node.put("dataType", field.dataType().name());
    node.put("validationStatus", field.validationStatus().name());
    issues(node.putArray("errors"), field.errors());
  }

  private static void issues(ArrayNode array, List<ValidationIssue> issues) {
    issues.forEach(
        issue ->
            array.addObject().put("code", issue.code().name()).put("message", issue.message()));
  }
}
