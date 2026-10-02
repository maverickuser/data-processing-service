package com.bondplatform.dataprocessing.shared.adapter.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;

/**
 * JSON conventions for every request and response: numbers are exact and never pass through
 * floating point.
 */
@Configuration(proxyBeanMethods = false)
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
