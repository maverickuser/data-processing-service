package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import java.util.List;

/**
 * Reads every scalar a JSON source contract selects from one parsed file (LLD sections 13.2 and
 * 13.4).
 *
 * <p>Each scalar is read on its own, so an invalid value is rejected alone and the others are kept.
 * A scalar whose path passes through a value of the wrong kind, such as text where {@code
 * instrumentsVo} must be an object, is rejected with that path and both kinds rather than reported
 * as missing (LLD section 13.7).
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

  /** Returns one canonical field per selected scalar, in contract order. */
  public List<JsonCanonicalField> extract(JsonValue root) {
    return scalars.stream().map(scalar -> scalar.read(root, reader)).toList();
  }

  /** A contract scalar with its parsed path. */
  private record Scalar(JsonField field, JsonPath path) {

    JsonCanonicalField read(JsonValue root, JsonFieldReader reader) {
      return switch (JsonPathExtractor.scalar(root, path)) {
        case JsonMatch.Found found ->
            reader.read(field.name(), field.path(), field.type(), found.value());
        case JsonMatch.WrongStructure wrong ->
            JsonFieldReader.unreachable(field.name(), field.path(), field.type(), wrong);
      };
    }
  }
}
