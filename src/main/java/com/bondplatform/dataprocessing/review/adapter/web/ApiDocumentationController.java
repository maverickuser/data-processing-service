package com.bondplatform.dataprocessing.review.adapter.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the read API's documentation (LLD 23.2): a Swagger UI page at {@code /docs} and the read
 * OpenAPI document it shows at {@code /docs/openapi.yaml}. Only these fixed files are served, all
 * from the deployed package, so the document always describes the deployed API. The submission API
 * is internal and is not published.
 */
@RestController
@ConditionalOnBooleanProperty("data-processing.api.read-routes")
public class ApiDocumentationController {

  static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
  static final MediaType CSS = new MediaType("text", "css", StandardCharsets.UTF_8);
  static final MediaType JAVASCRIPT = new MediaType("text", "javascript", StandardCharsets.UTF_8);
  static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);

  private static final String SWAGGER_UI_PROPERTIES =
      "META-INF/maven/org.webjars/swagger-ui/pom.properties";

  private final Resource page = new ClassPathResource("api-docs/index.html");
  private final Resource initializer = new ClassPathResource("api-docs/initializer.js");
  private final Resource document =
      new ClassPathResource("api-docs/data-processing-service-read-openapi.yaml");
  private final Resource styleSheet;
  private final Resource bundle;

  /**
   * Creates the controller.
   *
   * @throws IllegalStateException if Swagger UI is not packaged, so the function fails to start
   *     rather than serving a broken page
   */
  public ApiDocumentationController() {
    this(swaggerUiVersion());
  }

  /** Creates the controller for the given packaged Swagger UI version. */
  ApiDocumentationController(String swaggerUiVersion) {
    String swaggerUi = "META-INF/resources/webjars/swagger-ui/" + swaggerUiVersion + "/";
    this.styleSheet = new ClassPathResource(swaggerUi + "swagger-ui.css");
    this.bundle = new ClassPathResource(swaggerUi + "swagger-ui-bundle.js");
    for (Resource resource : new Resource[] {page, initializer, document, styleSheet, bundle}) {
      if (!resource.exists()) {
        throw new IllegalStateException("The API documentation is not packaged: " + resource);
      }
    }
  }

  /** Returns the Swagger UI page. */
  @GetMapping("/docs")
  public ResponseEntity<Resource> page() {
    return file(page, HTML);
  }

  /** Returns the read OpenAPI document, as committed in {@code docs/specs}. */
  @GetMapping("/docs/openapi.yaml")
  public ResponseEntity<Resource> document() {
    return file(document, YAML);
  }

  /** Returns the script that starts Swagger UI on the read OpenAPI document. */
  @GetMapping("/docs/initializer.js")
  public ResponseEntity<Resource> initializer() {
    return file(initializer, JAVASCRIPT);
  }

  /** Returns Swagger UI's style sheet. */
  @GetMapping("/docs/swagger-ui.css")
  public ResponseEntity<Resource> styleSheet() {
    return file(styleSheet, CSS);
  }

  /** Returns Swagger UI's script. */
  @GetMapping("/docs/swagger-ui-bundle.js")
  public ResponseEntity<Resource> bundle() {
    return file(bundle, JAVASCRIPT);
  }

  // Revalidated on every use, so a deployment shows its own document and Swagger UI at once
  private static ResponseEntity<Resource> file(Resource resource, MediaType type) {
    return ResponseEntity.ok()
        .contentType(type)
        .cacheControl(CacheControl.noCache())
        .body(resource);
  }

  private static String swaggerUiVersion() {
    Properties properties = new Properties();
    try (InputStream in = new ClassPathResource(SWAGGER_UI_PROPERTIES).getInputStream()) {
      properties.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("Swagger UI is not packaged", e);
    }
    String version = properties.getProperty("version");
    if (version == null || version.isBlank()) {
      throw new IllegalStateException("Swagger UI's packaged version is unknown");
    }
    return version;
  }
}
