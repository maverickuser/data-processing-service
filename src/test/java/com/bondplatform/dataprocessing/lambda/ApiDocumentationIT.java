package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-READ-09: the documentation routes, sent as API Gateway events through the Lambda entry point,
 * serve Swagger UI and the read OpenAPI document under the documentation pages' own policy, and
 * nothing else from the package.
 */
class ApiDocumentationIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DOCUMENTATION_POLICY =
      "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; frame-ancestors 'none'";

  private static ApiGatewayHandler handler;

  @BeforeAll
  static void startApplication() {
    handler = new ApiGatewayHandler();
  }

  @Test
  void pageIsServedUnderTheDocumentationPolicy() throws IOException {
    JsonNode response = send("/docs");

    assertThat(response.get("statusCode").asInt()).isEqualTo(200);
    assertThat(header(response, "Content-Type")).startsWith("text/html");
    assertThat(header(response, "Content-Security-Policy")).isEqualTo(DOCUMENTATION_POLICY);
    assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(body(response)).contains("/docs/swagger-ui-bundle.js");
  }

  @Test
  void documentIsTheCommittedReadOpenApiDocument() throws IOException {
    JsonNode response = send("/docs/openapi.yaml");

    assertThat(response.get("statusCode").asInt()).isEqualTo(200);
    assertThat(header(response, "Content-Type")).startsWith("application/yaml");
    assertThat(body(response))
        .isEqualTo(
            Files.readString(Path.of("docs/specs/data-processing-service-read-openapi.yaml")));
  }

  @Test
  void swaggerUiScriptAndStyleSheetAreServed() throws IOException {
    JsonNode script = send("/docs/swagger-ui-bundle.js");
    JsonNode styleSheet = send("/docs/swagger-ui.css");

    assertThat(header(script, "Content-Type")).startsWith("text/javascript");
    assertThat(body(script)).contains("SwaggerUIBundle");
    assertThat(header(styleSheet, "Content-Type")).startsWith("text/css");
    assertThat(body(styleSheet)).contains(".swagger-ui");
  }

  @Test
  void unknownDocumentationPathIsNotFound() throws IOException {
    JsonNode response = send("/docs/swagger-initializer.js");

    assertThat(response.get("statusCode").asInt()).isEqualTo(404);
    assertThat(header(response, "Content-Type")).startsWith("application/problem+json");
  }

  @Test
  void unchangedFileIsNotSentAgain() throws IOException {
    String entityTag = header(send("/docs/swagger-ui-bundle.js"), "ETag");

    JsonNode response =
        send(HttpApiEvent.get("/docs/swagger-ui-bundle.js").header("if-none-match", entityTag));

    assertThat(response.get("statusCode").asInt()).isEqualTo(304);
    assertThat(response.path("body").asString()).isEmpty();
  }

  @Test
  void packagedLibraryFilesAreNotServed() throws IOException {
    for (String path :
        new String[] {
          "/webjars/swagger-ui/5.33.1/index.html",
          "/webjars/swagger-ui/5.33.1/swagger-initializer.js",
          "/META-INF/resources/webjars/swagger-ui/5.33.1/index.html"
        }) {
      JsonNode response = send(path);
      assertThat(response.get("statusCode").asInt()).as(path).isEqualTo(404);
      assertThat(header(response, "Content-Type")).as(path).startsWith("application/problem+json");
    }
  }

  private static JsonNode send(String path) throws IOException {
    return send(HttpApiEvent.get(path));
  }

  private static JsonNode send(HttpApiEvent event) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    handler.handleRequest(event.toStream(), output, new FixedLambdaContext());
    return JSON.readTree(output.toString(StandardCharsets.UTF_8));
  }

  private static String header(JsonNode response, String name) {
    return response.get("headers").get(name).asString();
  }

  // The container may base64-encode a response it does not treat as text; API Gateway decodes it
  private static String body(JsonNode response) {
    String body = response.get("body").asString();
    return response.path("isBase64Encoded").asBoolean()
        ? new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)
        : body;
  }
}
