package com.bondplatform.dataprocessing.lambda;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** Builds API Gateway HTTP API (payload format 2.0) events for tests. */
final class HttpApiEvent {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String method;
  private final String path;
  private final Map<String, String> headers = new LinkedHashMap<>();
  private String rawQueryString = "";
  private @Nullable String body;

  private HttpApiEvent(String method, String path) {
    this.method = method;
    this.path = path;
    headers.put("host", "processing.kagent.app");
  }

  static HttpApiEvent get(String path) {
    return new HttpApiEvent("GET", path);
  }

  static HttpApiEvent post(String path) {
    return new HttpApiEvent("POST", path);
  }

  HttpApiEvent query(String rawQueryString) {
    this.rawQueryString = rawQueryString;
    return this;
  }

  HttpApiEvent header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  HttpApiEvent body(String contentType, String body) {
    this.body = body;
    return header("content-type", contentType);
  }

  InputStream toStream() {
    Map<String, Object> http = new LinkedHashMap<>();
    http.put("method", method);
    http.put("path", path);
    http.put("protocol", "HTTP/1.1");
    http.put("sourceIp", "203.0.113.1");
    http.put("userAgent", "test");

    Map<String, Object> requestContext = new LinkedHashMap<>();
    requestContext.put("accountId", "000000000000");
    requestContext.put("apiId", "test");
    requestContext.put("domainName", "processing.kagent.app");
    requestContext.put("http", http);
    requestContext.put("requestId", "test-request");
    requestContext.put("routeKey", "$default");
    requestContext.put("stage", "$default");
    requestContext.put("timeEpoch", 1790935200000L);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("version", "2.0");
    event.put("routeKey", "$default");
    event.put("rawPath", path);
    event.put("rawQueryString", rawQueryString);
    event.put("headers", headers);
    event.put("requestContext", requestContext);
    event.put("isBase64Encoded", false);
    if (body != null) {
      event.put("body", body);
    }
    return new ByteArrayInputStream(
        JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
  }
}
