package com.bondplatform.dataprocessing.source.adapter.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StrictJsonObjectParserTest {

  private final StrictJsonObjectParser parser = new StrictJsonObjectParser();

  @Test
  void readsOneObjectAndRefusesAnythingElse() {
    assertThat(parser.parse("{\"a\": \"b\"}".getBytes(StandardCharsets.UTF_8)))
        .hasValueSatisfying(object -> assertThat(object).containsEntry("a", "b"));
    assertThat(parser.parse("{\"a\": 1, \"a\": 2}".getBytes(StandardCharsets.UTF_8))).isEmpty();
  }
}
