package com.bondplatform.dataprocessing.contract.adapter.yaml;

import com.bondplatform.dataprocessing.contract.domain.ContractFormatException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * A YAML mapping read with type checks, so that a wrongly shaped contract fails with the location
 * of the problem rather than a class-cast error.
 *
 * <p>Every key must be text, and a reader states which keys it understands with {@link
 * #allowingOnly}: a misspelled or unsupported key is an error, never silently ignored.
 */
final class YamlMapping {

  private static final String ROOT = "(root)";

  private final String location;
  private final Map<String, Object> values;

  private YamlMapping(String location, Map<String, Object> values) {
    this.location = location;
    this.values = values;
  }

  /** Wraps the root of a parsed YAML document. */
  static YamlMapping root(@Nullable Object document) {
    return of(ROOT, document);
  }

  /**
   * Returns this mapping after checking that it has no key outside the given set.
   *
   * @throws ContractFormatException naming the first key that is not allowed
   */
  YamlMapping allowingOnly(String... allowedKeys) {
    Set<String> allowed = Set.of(allowedKeys);
    for (String key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw new ContractFormatException(at(key), "is not a supported key");
      }
    }
    return this;
  }

  /** Returns the keys in file order. */
  Set<String> keys() {
    return values.keySet();
  }

  /** Returns the text at a key that must be present. Numbers and booleans are not text. */
  String text(String key) {
    Object value = required(key);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new ContractFormatException(at(key), "expected non-blank text");
  }

  /** Returns the whole number at a key that must be present. */
  long wholeNumber(String key) {
    Object value = required(key);
    if (value instanceof Integer || value instanceof Long) {
      return ((Number) value).longValue();
    }
    throw new ContractFormatException(at(key), "expected a whole number");
  }

  /** Returns the boolean at a key that must be present. */
  boolean flag(String key) {
    if (required(key) instanceof Boolean flag) {
      return flag;
    }
    throw new ContractFormatException(at(key), "expected true or false");
  }

  /** Returns the nested mapping at a key that must be present. */
  YamlMapping mapping(String key) {
    return of(at(key), required(key));
  }

  /** Returns the list of text at a key, or an empty list when the key is absent. */
  List<String> textList(String key) {
    if (!values.containsKey(key)) {
      return List.of();
    }
    if (values.get(key) instanceof List<?> list
        && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw new ContractFormatException(at(key), "expected a list of text");
  }

  /** Returns the list of mappings at a key, or an empty list when the key is absent. */
  List<YamlMapping> mappingList(String key) {
    if (!values.containsKey(key)) {
      return List.of();
    }
    if (values.get(key) instanceof List<?> list) {
      return IntStream.range(0, list.size())
          .mapToObj(index -> of(at(key) + "[" + index + "]", list.get(index)))
          .toList();
    }
    throw new ContractFormatException(at(key), "expected a list");
  }

  /** Returns the text-to-text mapping at a key, or an empty map when the key is absent. */
  Map<String, String> textMap(String key) {
    if (!values.containsKey(key)) {
      return Map.of();
    }
    YamlMapping nested = mapping(key);
    Map<String, String> result = new LinkedHashMap<>();
    nested.keys().forEach(name -> result.put(name, nested.text(name)));
    return result;
  }

  /** Returns whether the key is present, whatever its value. */
  boolean has(String key) {
    return values.containsKey(key);
  }

  /** Returns where this mapping is in the contract, for error messages. */
  String location() {
    return location;
  }

  private Object required(String key) {
    Object value = values.get(key);
    if (value == null) {
      throw new ContractFormatException(at(key), "is required");
    }
    return value;
  }

  private String at(String key) {
    return location.equals(ROOT) ? key : location + "." + key;
  }

  private static YamlMapping of(String location, @Nullable Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new ContractFormatException(location, "expected a mapping");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new ContractFormatException(
            location, "has the key '" + entry.getKey() + "', which is not text");
      }
      values.put(key, entry.getValue());
    }
    return new YamlMapping(location, values);
  }
}
