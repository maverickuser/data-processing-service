package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every scalar a JSON source contract selects from one parsed file (LLD sections 13.2 and
 * 13.4).
 *
 * <p>Each scalar is read on its own, so an invalid value is rejected alone and the others are kept.
 * A scalar whose path passes through a value of the wrong kind, such as text where {@code
 * instrumentsVo} must be an object, fails rather than counting as missing, and the blocking path is
 * reported once however many scalars sit under it (LLD section 13.7).
 */
public final class ScalarExtractor {

  private final List<Scalar> scalars;
  private final JsonFieldReader reader;

  /** Prepares the scalars of a contract that has already passed contract validation. */
  public ScalarExtractor(SourceContract.Json contract, RuleRegistry registry) {
    this.scalars =
        contract.scalars().stream()
            .map(field -> new Scalar(field, JsonPath.parse(field.path())))
            .toList();
    this.reader = new JsonFieldReader(registry);
  }

  /**
   * Returns one canonical field per selected scalar, in contract order, and the structure errors.
   */
  public ScalarExtraction extract(JsonValue root) {
    List<JsonCanonicalField> fields = new ArrayList<>(scalars.size());
    Map<String, StructureIssue> issues = new LinkedHashMap<>();
    for (Scalar scalar : scalars) {
      JsonField field = scalar.field();
      switch (JsonPathExtractor.scalar(root, scalar.path())) {
        case JsonMatch.Found found ->
            fields.add(reader.read(field.name(), field.path(), field.type(), found.value()));
        case JsonMatch.WrongStructure wrong -> {
          fields.add(JsonFieldReader.unreachable(field.name(), field.path(), field.type()));
          issues.putIfAbsent(wrong.path(), StructureIssue.of(wrong));
        }
      }
    }
    return new ScalarExtraction(fields, List.copyOf(issues.values()));
  }

  /**
   * The scalars of one file.
   *
   * @param fields one per selected scalar, in contract order
   * @param structureIssues one per path whose value had the wrong kind
   */
  public record ScalarExtraction(
      List<JsonCanonicalField> fields, List<StructureIssue> structureIssues) {

    /** Copies the lists. */
    public ScalarExtraction {
      fields = List.copyOf(fields);
      structureIssues = List.copyOf(structureIssues);
    }
  }

  /** A contract scalar with its parsed path. */
  private record Scalar(JsonField field, JsonPath path) {}
}
