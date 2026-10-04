package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonArray;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNull;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonObject;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Finds what a contract path selects in a parsed JSON document (LLD sections 13.2 and 13.7).
 *
 * <p>Property names match exactly, including case. A path whose property, or whose enclosing
 * object, is missing or {@code null} selects nothing; that is "no update", not an error. A value of
 * the wrong kind where the path needs an object or an array is reported as a structural error with
 * its path and both kinds.
 */
public final class JsonPathExtractor {

  private static final String OBJECT = "object";
  private static final String ARRAY = "array";

  private JsonPathExtractor() {}

  /**
   * Returns the value a scalar path selects.
   *
   * @throws IllegalArgumentException if the path selects every entry of a collection
   */
  public static JsonMatch.Scalar scalar(JsonValue root, JsonPath path) {
    if (path.everyEntry()) {
      throw new IllegalArgumentException(path + " selects a collection, not a scalar");
    }
    return switch (walk(root, path)) {
      case Walked.Reached reached -> new JsonMatch.Found(reached.value().toSourceValue());
      case Walked.Missing ignored -> new JsonMatch.Found(new SourceValue.Missing());
      case Walked.Blocked blocked -> blocked.error();
    };
  }

  /**
   * Returns the entries a collection path selects.
   *
   * @throws IllegalArgumentException if the path does not end in {@code [*]}
   */
  public static JsonMatch.Collection collection(JsonValue root, JsonPath path) {
    if (!path.everyEntry()) {
      throw new IllegalArgumentException(path + " selects a scalar, not a collection");
    }
    return switch (walk(root, path)) {
      case Walked.Reached reached -> entries(reached.value(), reached.path());
      case Walked.Missing ignored -> new JsonMatch.Absent();
      case Walked.Blocked blocked -> blocked.error();
    };
  }

  private static JsonMatch.Collection entries(JsonValue value, String at) {
    return switch (value) {
      case JsonNull ignored -> new JsonMatch.Absent();
      case JsonArray array -> {
        List<JsonMatch.Entry> entries = new ArrayList<>(array.elements().size());
        for (int i = 0; i < array.elements().size(); i++) {
          entries.add(new JsonMatch.Entry(at + "[" + i + "]", array.elements().get(i)));
        }
        yield new JsonMatch.Entries(entries);
      }
      default -> wrongKind(at, ARRAY, value);
    };
  }

  private static Walked walk(JsonValue root, JsonPath path) {
    JsonValue current = root;
    String at = "$";
    for (String property : path.properties()) {
      if (current instanceof JsonNull) {
        return new Walked.Missing();
      }
      if (!(current instanceof JsonObject object)) {
        return new Walked.Blocked(wrongKind(at, OBJECT, current));
      }
      @Nullable JsonValue next = object.properties().get(property);
      if (next == null) {
        return new Walked.Missing();
      }
      current = next;
      at = at + "." + property;
    }
    return new Walked.Reached(current, at);
  }

  private static JsonMatch.WrongStructure wrongKind(String at, String expected, JsonValue actual) {
    return new JsonMatch.WrongStructure(at, expected, actual.toSourceValue().kindName());
  }

  /** How far a walk along a path's properties got. */
  private sealed interface Walked {

    /** Every property was found; {@code value} is at {@code path}. */
    record Reached(JsonValue value, String path) implements Walked {}

    /** A property, or an object that would hold it, is missing or {@code null}. */
    record Missing() implements Walked {}

    /** A value on the way is not an object. */
    record Blocked(JsonMatch.WrongStructure error) implements Walked {}
  }
}
