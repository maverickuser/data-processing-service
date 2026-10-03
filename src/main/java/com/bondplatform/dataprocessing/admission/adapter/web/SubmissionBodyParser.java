package com.bondplatform.dataprocessing.admission.adapter.web;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a submission body into plain maps, lists, strings, numbers, and booleans.
 *
 * <p>It is stricter than the application's general JSON handling, because the parsed event is what
 * a replay is compared against: the body must be UTF-8, one JSON object and nothing after it, with
 * no name repeated inside any object. Numbers are kept exactly: whole numbers as {@code Integer},
 * {@code Long}, or {@code BigInteger}, and numbers with a fraction or exponent as {@code
 * BigDecimal}; never floating point.
 */
final class SubmissionBodyParser {

  /** Deeper nesting than any valid submission has; a deeper body is malformed. */
  static final int MAX_NESTING_DEPTH = 32;

  private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT =
      new TypeReference<>() {};
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private SubmissionBodyParser() {}

  /** Returns the parsed object, or empty when the body is not acceptable JSON. */
  static Optional<Map<String, Object>> parse(byte[] body) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      return Optional.ofNullable(JSON.readValue(text, JSON_OBJECT));
    } catch (CharacterCodingException | JacksonException e) {
      // The caller answers 400; the parser's message may quote the body, so it is not passed on.
      return Optional.empty();
    }
  }
}
