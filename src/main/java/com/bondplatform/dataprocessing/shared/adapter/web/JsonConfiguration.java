package com.bondplatform.dataprocessing.shared.adapter.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;

/**
 * JSON conventions for every request and response: numbers are exact and never pass through
 * floating point.
 *
 * <p>Timestamps need no configuration because response models carry recorded times only as {@link
 * java.time.Instant}, which is always written in UTC ({@code 2026-09-27T14:31:02Z}). Do not put
 * {@code OffsetDateTime} or {@code ZonedDateTime} in a response model: they are written with their
 * own offset.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicApiProperties.class)
public class JsonConfiguration {

  /**
   * Writes decimals in plain notation ({@code 1000}, never {@code 1E+3}) and reads every JSON
   * number with a fraction as a {@code BigDecimal}.
   */
  @Bean
  public JsonMapperBuilderCustomizer exactNumbers() {
    return builder ->
        builder
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }
}
