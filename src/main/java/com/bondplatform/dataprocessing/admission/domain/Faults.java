package com.bondplatform.dataprocessing.admission.domain;

import com.bondplatform.dataprocessing.admission.domain.SubmissionError.Code;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Collects the faults found while reading a submission, and reads properties with type checks so
 * that a wrong value becomes a recorded fault rather than an exception.
 *
 * <p>Locations in the body are RFC 6901 JSON Pointers.
 */
final class Faults {

  private static final String REQUIRED = "Is required.";

  private final List<SubmissionError> errors = new ArrayList<>();

  /** Returns the pointer of a property inside the object at {@code pointer}. */
  static String child(String pointer, String name) {
    return pointer + "/" + name.replace("~", "~0").replace("/", "~1");
  }

  /** Returns the faults recorded so far, in the order they were found. */
  List<SubmissionError> errors() {
    return List.copyOf(errors);
  }

  boolean isEmpty() {
    return errors.isEmpty();
  }

  void inBody(String pointer, Code code, String message) {
    errors.add(SubmissionError.inBody(pointer, code, message));
  }

  void inHeader(String header, Code code, String message) {
    errors.add(SubmissionError.inHeader(header, code, message));
  }

  /** Returns the non-blank text at a property, or null after recording why it is not. */
  @Nullable String text(Map<String, Object> object, String pointer, String name) {
    Object value = object.get(name);
    if (value == null) {
      inBody(
          child(pointer, name),
          object.containsKey(name) ? Code.INVALID_VALUE : Code.REQUIRED,
          object.containsKey(name) ? "Must not be null." : REQUIRED);
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      inBody(child(pointer, name), Code.INVALID_VALUE, "Expected non-blank text.");
      return null;
    }
    return text;
  }

  /** Records a fault unless the property holds exactly the expected text. */
  void constant(Map<String, Object> object, String pointer, String name, String expected) {
    String value = text(object, pointer, name);
    if (value != null && !value.equals(expected)) {
      inBody(child(pointer, name), Code.UNSUPPORTED_VALUE, "Expected " + expected + ".");
    }
  }

  /**
   * Returns the object at a property, or empty after recording why it is not one. The caller
   * validates the children only when the object is present, so one missing parent is one fault.
   */
  Optional<Map<String, Object>> object(Map<String, Object> object, String pointer, String name) {
    Object value = object.get(name);
    if (value == null) {
      inBody(child(pointer, name), Code.REQUIRED, REQUIRED);
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> map)) {
      inBody(child(pointer, name), Code.INVALID_VALUE, "Expected an object.");
      return Optional.empty();
    }
    Map<String, Object> typed = new LinkedHashMap<>();
    map.forEach((key, entry) -> typed.put(String.valueOf(key), entry));
    return Optional.of(typed);
  }

  /** Records a fault for each property outside the allowed set, in name order. */
  void onlyProperties(Map<String, Object> object, String pointer, Set<String> allowed) {
    object.keySet().stream()
        .filter(name -> !allowed.contains(name))
        .sorted()
        .forEach(
            name ->
                inBody(child(pointer, name), Code.UNKNOWN_PROPERTY, "Is not an allowed property."));
  }
}
