package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonArray;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonBoolean;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNull;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonNumber;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonObject;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue.JsonString;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValueTest {

  // LLD 16: an object or array keeps its whole structure as evidence
  @Test
  void writesCompactJsonInDocumentOrderWithExactNumbers() {
    Map<String, JsonValue> properties = new LinkedHashMap<>();
    properties.put("z", new JsonNumber(new BigDecimal("89400.00")));
    properties.put("a \"quoted\"", new JsonArray(List.of(new JsonBoolean(true), new JsonNull())));
    properties.put("n", new JsonObject(Map.of("s", new JsonString("x"))));

    JsonObject object = new JsonObject(properties);

    String json = "{\"z\":89400.00,\"a \\\"quoted\\\"\":[true,null],\"n\":{\"s\":\"x\"}}";
    assertThat(object.toJson()).isEqualTo(json);
    assertThat(object.toSourceValue())
        .isEqualTo(new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT, json));
  }

  @Test
  void becomesTheSourceValueOfItsKind() {
    assertThat(new JsonObject(Map.of()).toSourceValue())
        .isEqualTo(new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT, "{}"));
    assertThat(new JsonArray(List.of()).toSourceValue())
        .isEqualTo(new SourceValue.Structured(SourceValue.Structured.Kind.ARRAY, "[]"));
    assertThat(new JsonString("8.94%").toSourceValue()).isEqualTo(new SourceValue.Text("8.94%"));
    assertThat(new JsonNumber(new BigDecimal("8.94")).toSourceValue())
        .isEqualTo(new SourceValue.Decimal(new BigDecimal("8.94")));
    assertThat(new JsonBoolean(false).toSourceValue()).isEqualTo(new SourceValue.Bool(false));
    assertThat(new JsonNull().toSourceValue()).isEqualTo(new SourceValue.Null());
  }

  @Test
  void objectAndArrayAreImmutableCopies() {
    Map<String, JsonValue> properties = new LinkedHashMap<>();
    properties.put("b", new JsonNull());
    properties.put("a", new JsonNull());
    List<JsonValue> elements = new ArrayList<>(List.of(new JsonNull()));
    JsonObject object = new JsonObject(properties);
    final JsonArray array = new JsonArray(elements);

    properties.clear();
    elements.clear();

    assertThat(object.properties().keySet()).containsExactly("b", "a");
    assertThat(array.elements()).hasSize(1);
    assertThatThrownBy(() -> object.properties().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
