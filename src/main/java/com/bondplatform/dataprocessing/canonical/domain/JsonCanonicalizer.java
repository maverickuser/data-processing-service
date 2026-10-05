package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.FieldPresence;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stage 1 of JSON processing for one source file: applies every selected path of the dataset's
 * contract, whatever the file is called, and validates each value on its own (LLD sections 13.1,
 * 13.4, and 13.7).
 *
 * <p>A malformed file is skipped with {@code MALFORMED_JSON}. The ISIN comes from the file name; an
 * ISIN inside the payload that differs from it is a warning only and changes nothing.
 */
public final class JsonCanonicalizer {

  private static final JsonPath PAYLOAD_ISIN = JsonPath.parse("$.isin");

  private final ScalarExtractor scalars;
  private final CollectionExtractor collections;

  /** Prepares a contract that has already passed contract validation. */
  public JsonCanonicalizer(SourceContract.Json contract, RuleRegistry registry) {
    this.scalars = new ScalarExtractor(contract, registry);
    this.collections = new CollectionExtractor(contract, registry);
  }

  /** Returns the file's canonical content, or why it is skipped. */
  public JsonFileCanonical canonicalize(Isin filenameIsin, JsonRead read) {
    return switch (read) {
      case JsonRead.Malformed malformed ->
          new JsonFileCanonical.Skipped(
              new ValidationIssue(ErrorCode.MALFORMED_JSON, malformed.detail() + "."));
      case JsonRead.Parsed parsed -> canonicalize(filenameIsin, parsed.root());
    };
  }

  private JsonFileCanonical.Read canonicalize(Isin filenameIsin, JsonValue root) {
    ScalarExtractor.ScalarExtraction scalarFields = scalars.extract(root);
    CollectionExtractor.CollectionExtraction entries = collections.extract(root);
    // A path that blocks both scalars and collections, such as a root that is not an object, is
    // reported once.
    Map<String, StructureIssue> issues = new LinkedHashMap<>();
    Stream.concat(scalarFields.structureIssues().stream(), entries.structureIssues().stream())
        .forEach(issue -> issues.putIfAbsent(issue.path(), issue));
    List<String> warnings = new ArrayList<>();
    if (JsonPathExtractor.scalar(root, PAYLOAD_ISIN) instanceof JsonMatch.Found found
        && found.value() instanceof SourceValue.Text text
        && FieldPresence.of(text) == FieldPresence.PRESENT
        && !Isin.of(text.value()).equals(filenameIsin)) {
      warnings.add(
          "The payload ISIN "
              + Isin.of(text.value())
              + " differs from the file name's ISIN "
              + filenameIsin
              + "; the file name's is used.");
    }
    return new JsonFileCanonical.Read(
        scalarFields.fields(), entries.entries(), List.copyOf(issues.values()), warnings);
  }
}
