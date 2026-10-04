package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  @Test
  void writesObjectKeysInSortedOrderWithoutWhitespace() {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "t");
    event.put("data", Map.of("run_id", "run_1"));
    event.put("id", "i");

    assertThat(CanonicalJson.of(event))
        .isEqualTo("{\"data\":{\"run_id\":\"run_1\"},\"id\":\"i\",\"type\":\"t\"}");
  }

  @Test
  void sameContentInAnotherKeyOrderIsTheSameText() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("a", 1);
    first.put("b", List.of(true, "x"));
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("b", List.of(true, "x"));
    second.put("a", 1);

    assertThat(CanonicalJson.of(first)).isEqualTo(CanonicalJson.of(second));
  }

  @Test
  void keepsArrayOrderAndScalarsAsParsed() {
    assertThat(CanonicalJson.of(Arrays.asList(2, 1, null, false, new BigDecimal("1.50"), "z")))
        .isEqualTo("[2,1,null,false,1.50,\"z\"]");
    assertThat(CanonicalJson.of(new BigDecimal("1.0")))
        .isNotEqualTo(CanonicalJson.of(new BigDecimal("1")));
  }

  @Test
  void escapesQuotesBackslashesAndControlCharacters() {
    assertThat(CanonicalJson.of("a\"b\\c\nd\re\tf\u0001g"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"");
    assertThat(CanonicalJson.of("ü €")).isEqualTo("\"ü €\"");
  }

  @Test
  void writesNullAndEmptyStructures() {
    assertThat(CanonicalJson.of(null)).isEqualTo("null");
    assertThat(CanonicalJson.of(Map.of())).isEqualTo("{}");
    assertThat(CanonicalJson.of(List.of())).isEqualTo("[]");
  }

  @Test
  void rejectsValuesThatAreNotJson() {
    assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJson.of(new Object()));
  }
}
