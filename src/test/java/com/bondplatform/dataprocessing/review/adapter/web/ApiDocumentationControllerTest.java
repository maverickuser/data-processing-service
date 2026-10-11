package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** U-REV-07: the documentation routes serve fixed, packaged files with their media types. */
class ApiDocumentationControllerTest {

  private final ApiDocumentationController controller = new ApiDocumentationController();

  @Test
  void pageLoadsSwaggerUiAndItsStarterFromThisOrigin() throws IOException {
    String page = text(controller.page(), ApiDocumentationController.HTML);

    assertThat(page)
        .contains(
            "src=\"/docs/swagger-ui-bundle.js\"",
            "src=\"/docs/initializer.js\"",
            "href=\"/docs/swagger-ui.css\"")
        .doesNotContain("http://", "https://");
  }

  @Test
  void documentIsTheCommittedReadOpenApiDocument() throws IOException {
    String document = text(controller.document(), ApiDocumentationController.YAML);

    assertThat(document)
        .isEqualTo(
            Files.readString(Path.of("docs/specs/data-processing-service-read-openapi.yaml")))
        .doesNotContain("/v1/event-ingestions");
  }

  @Test
  void starterShowsTheServedDocumentWithoutTheOnlineValidator() throws IOException {
    String starter = text(controller.initializer(), ApiDocumentationController.JAVASCRIPT);

    assertThat(starter).contains("url: \"/docs/openapi.yaml\"", "validatorUrl: null");
  }

  @Test
  void swaggerUiFilesAreServedFromThePackage() throws IOException {
    assertThat(text(controller.styleSheet(), ApiDocumentationController.CSS))
        .contains(".swagger-ui");
    assertThat(text(controller.bundle(), ApiDocumentationController.JAVASCRIPT))
        .contains("SwaggerUIBundle");
  }

  @Test
  void filesAreRevalidatedByContentSoEachDeploymentShowsItsOwnDocument() {
    assertThat(controller.document().getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(controller.document().getHeaders().getETag())
        .matches("\"[0-9a-f]{32}\"")
        .isEqualTo(new ApiDocumentationController().document().getHeaders().getETag())
        .isNotEqualTo(controller.bundle().getHeaders().getETag());
  }

  @Test
  void missingSwaggerUiStopsTheFunctionFromStarting() {
    assertThatThrownBy(() -> new ApiDocumentationController("0.0.0"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not packaged");
  }

  private static String text(ResponseEntity<Resource> response, MediaType type) throws IOException {
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(type);
    return Objects.requireNonNull(response.getBody()).getContentAsString(StandardCharsets.UTF_8);
  }
}
