package com.bondplatform.dataprocessing.source.application;

import java.util.Map;
import java.util.Optional;

/** Parses a JSON document that must be one object, into plain maps, lists, and values. */
public interface JsonObjectParser {

  /** Returns the object, or empty if the document is not exactly one well-formed JSON object. */
  Optional<Map<String, Object>> parse(byte[] document);
}
