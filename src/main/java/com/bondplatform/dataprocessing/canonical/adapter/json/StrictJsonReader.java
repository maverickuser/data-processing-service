package com.bondplatform.dataprocessing.canonical.adapter.json;

import com.bondplatform.dataprocessing.canonical.application.JsonDocumentReader;
import com.bondplatform.dataprocessing.canonical.domain.JsonRead;
import com.bondplatform.dataprocessing.canonical.domain.JsonValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a JSON source file with Jackson's streaming parser into a {@link JsonValue}.
 *
 * <p>The file must be UTF-8, optionally starting with a byte order mark, and hold exactly one JSON
 * value. A property name repeated inside one object makes the file malformed rather than letting
 * one value win; the same name in different objects is fine. Every number is read as a {@code
 * BigDecimal} from its text. Nesting deeper than {@value #MAX_NESTING_DEPTH} levels is malformed.
 * Error details give a line and column but never quote the file, which may hold values not meant
 * for error messages.
 */
public final class StrictJsonReader implements JsonDocumentReader {

  private static final char BYTE_ORDER_MARK = '﻿';

  /** Far deeper than any NSDL payload, which nests about five levels. */
  public static final int MAX_NESTING_DEPTH = 32;

  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
                  .build())
          .build();

  @Override
  public JsonRead read(byte[] content) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (CharacterCodingException e) {
      return new JsonRead.Malformed("The file is not valid UTF-8");
    }
    if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
      text = text.substring(1);
    }
    try (JsonParser parser = JSON.createParser(text)) {
      @Nullable JsonToken first = parser.nextToken();
      if (first == null) {
        return new JsonRead.Malformed("The file holds no JSON value");
      }
      JsonValue root = value(parser, first);
      if (parser.nextToken() != null) {
        return new JsonRead.Malformed(
            "More content follows the JSON value " + at(parser.currentTokenLocation()));
      }
      return new JsonRead.Parsed(root);
    } catch (MalformedContent e) {
      return new JsonRead.Malformed(e.detail);
    } catch (StreamConstraintsException e) {
      return new JsonRead.Malformed(
          "The file exceeds a limit of the JSON reader, such as nesting deeper than "
              + MAX_NESTING_DEPTH
              + " levels");
    } catch (JacksonException e) {
      // The parser's own message may quote the file, so only its location is passed on.
      return new JsonRead.Malformed("The file is not well-formed JSON " + at(e.getLocation()));
    }
  }

  private static JsonValue value(JsonParser parser, JsonToken token) {
    return switch (token) {
      case START_OBJECT -> object(parser);
      case START_ARRAY -> array(parser);
      case VALUE_STRING -> new JsonValue.JsonString(parser.getString());
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT ->
          new JsonValue.JsonNumber(parser.getDecimalValue());
      case VALUE_TRUE -> new JsonValue.JsonBoolean(true);
      case VALUE_FALSE -> new JsonValue.JsonBoolean(false);
      case VALUE_NULL -> new JsonValue.JsonNull();
      default ->
          throw new MalformedContent(
              "The file has an unexpected token " + at(parser.currentTokenLocation()));
    };
  }

  private static JsonValue object(JsonParser parser) {
    Map<String, JsonValue> properties = new LinkedHashMap<>();
    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = parser.currentName();
      TokenStreamLocation location = parser.currentTokenLocation();
      JsonValue value = value(parser, parser.nextToken());
      if (properties.putIfAbsent(name, value) != null) {
        throw new MalformedContent(
            "The property '" + name + "' appears twice in one object " + at(location));
      }
    }
    return new JsonValue.JsonObject(properties);
  }

  private static JsonValue array(JsonParser parser) {
    List<JsonValue> elements = new ArrayList<>();
    for (JsonToken token = parser.nextToken();
        token != JsonToken.END_ARRAY;
        token = parser.nextToken()) {
      elements.add(value(parser, token));
    }
    return new JsonValue.JsonArray(elements);
  }

  private static String at(@Nullable TokenStreamLocation location) {
    return location == null
        ? "at an unknown position"
        : "at line " + location.getLineNr() + ", column " + location.getColumnNr();
  }

  /** The file is well-formed for the parser but not acceptable, such as a repeated property. */
  private static final class MalformedContent extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String detail;

    MalformedContent(String detail) {
      super(null, null, false, false);
      this.detail = detail;
    }
  }
}
