package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPathTest {

  @Test
  void parsesScalarPath() {
    JsonPath path = JsonPath.parse("$.instrumentsVo.instruments.allotmentDate");

    assertThat(path.properties()).containsExactly("instrumentsVo", "instruments", "allotmentDate");
    assertThat(path.everyEntry()).isFalse();
    assertThat(path).hasToString("$.instrumentsVo.instruments.allotmentDate");
  }

  @Test
  void parsesCollectionPath() {
    JsonPath path = JsonPath.parse("$.listingDetails[*]");

    assertThat(path).isEqualTo(new JsonPath(List.of("listingDetails"), true));
    assertThat(path).hasToString("$.listingDetails[*]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "$", "$.", "issuerName", "$.a..b", "$.a[0]", "$.a[*].b", "$['a']", "$.1a"})
  void rejectsOtherPathSyntax(String path) {
    assertThatThrownBy(() -> JsonPath.parse(path))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported path '" + path + "'");
  }

  @Test
  void needsOneProperty() {
    assertThatThrownBy(() -> new JsonPath(List.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
