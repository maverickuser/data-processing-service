package com.bondplatform.dataprocessing.source.adapter.json;

import com.bondplatform.dataprocessing.shared.adapter.json.StrictJsonParser;
import com.bondplatform.dataprocessing.source.application.JsonObjectParser;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Parses source documents with the same strict rules as submissions. */
@Component
public class StrictJsonObjectParser implements JsonObjectParser {

  @Override
  public Optional<Map<String, Object>> parse(byte[] document) {
    return StrictJsonParser.parse(document);
  }
}
