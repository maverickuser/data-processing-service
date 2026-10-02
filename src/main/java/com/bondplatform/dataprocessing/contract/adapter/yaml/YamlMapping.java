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
 */
final class YamlMapping {

  private final String location;
  private final Map<?, ?> values;

  private YamlMapping(String location, Map<?, ?> values) {
    this.location = location;
    this.values = values;
  }

  /** Wraps the root of a parsed YAML document. */
  static YamlMapping root(@Nullable Object document) {
    return of("(root)", document);
  }

  /** Returns the keys in file order. */
  Set<String> keys() {
    Map<String, Object> ordered = new LinkedHashMap<>();
    values.forEach((key, value) -> ordered.put(String.valueOf(key), value));
    return ordered.keySet();
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
    Object value = values.get(key);
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw new ContractFormatException(at(key), "expected a list of text");
  }

  /** Returns the list of mappings at a key, or an empty list when the key is absent. */
  List<YamlMapping> mappingList(String key) {
    Object value = values.get(key);
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      return IntStream.range(0, list.size())
          .mapToObj(index -> of(at(key) + "[" + index + "]", list.get(index)))
          .toList();
    }
    throw new ContractFormatException(at(key), "expected a list");
  }

  /** Returns the text-to-text mapping at a key, or an empty map when the key is absent. */
  Map<String, String> textMap(String key) {
    if (values.get(key) == null) {
      return Map.of();
    }
    YamlMapping nested = mapping(key);
    Map<String, String> result = new LinkedHashMap<>();
    nested.keys().forEach(name -> result.put(name, nested.text(name)));
    return result;
  }

  /** Returns whether the key is present with a non-null value. */
  boolean has(String key) {
    return values.get(key) != null;
  }

  private Object required(String key) {
    Object value = values.get(key);
    if (value == null) {
      throw new ContractFormatException(at(key), "is required");
    }
    return value;
  }

  private String at(String key) {
    return location.equals("(root)") ? key : location + "." + key;
  }

  private static YamlMapping of(String location, @Nullable Object value) {
    if (value instanceof Map<?, ?> map) {
      return new YamlMapping(location, map);
    }
    throw new ContractFormatException(location, "expected a mapping");
  }
}
