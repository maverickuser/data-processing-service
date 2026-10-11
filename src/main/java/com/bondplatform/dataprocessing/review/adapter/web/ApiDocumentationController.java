package com.bondplatform.dataprocessing.review.adapter.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

  private final DocumentationFile page;
  private final DocumentationFile initializer;
  private final DocumentationFile document;
  private final DocumentationFile styleSheet;
  private final DocumentationFile bundle;

  /**
   * Creates the controller.
   *
   * @throws IllegalStateException if a documentation file is not packaged, so the function fails to
   *     start rather than serving a broken page
   */
  public ApiDocumentationController() {
    this(swaggerUiVersion());
  }

  /** Creates the controller for the given packaged Swagger UI version. */
  ApiDocumentationController(String swaggerUiVersion) {
    this.page = DocumentationFile.of("api-docs/index.html", HTML);
    this.initializer = DocumentationFile.of("api-docs/initializer.js", JAVASCRIPT);
    this.document =
        DocumentationFile.of("api-docs/data-processing-service-read-openapi.yaml", YAML);
    String swaggerUi = "META-INF/resources/webjars/swagger-ui/" + swaggerUiVersion + "/";
    this.styleSheet = DocumentationFile.of(swaggerUi + "swagger-ui.css", CSS);
    this.bundle = DocumentationFile.of(swaggerUi + "swagger-ui-bundle.js", JAVASCRIPT);
  }

  /** Returns the Swagger UI page. */
  @GetMapping("/docs")
  public ResponseEntity<Resource> page() {
    return page.response();
  }

  /** Returns the read OpenAPI document, as committed in {@code docs/specs}. */
  @GetMapping("/docs/openapi.yaml")
  public ResponseEntity<Resource> document() {
    return document.response();
  }

  /** Returns the script that starts Swagger UI on the read OpenAPI document. */
  @GetMapping("/docs/initializer.js")
  public ResponseEntity<Resource> initializer() {
    return initializer.response();
  }

  /** Returns Swagger UI's style sheet. */
  @GetMapping("/docs/swagger-ui.css")
  public ResponseEntity<Resource> styleSheet() {
    return styleSheet.response();
  }

  /** Returns Swagger UI's script. */
  @GetMapping("/docs/swagger-ui-bundle.js")
  public ResponseEntity<Resource> bundle() {
    return bundle.response();
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

  /**
   * One packaged file with its media type and an entity tag of its content. A browser revalidates
   * the file on every use and gets {@code 304} while the deployment is unchanged, so a deployment
   * shows its own document at once without every page view downloading Swagger UI again.
   */
  private record DocumentationFile(Resource resource, MediaType type, String entityTag) {

    static DocumentationFile of(String location, MediaType type) {
      Resource resource = new ClassPathResource(location);
      try {
        byte[] digest =
            MessageDigest.getInstance("SHA-256").digest(resource.getContentAsByteArray());
        return new DocumentationFile(
            resource, type, "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"");
      } catch (IOException e) {
        throw new IllegalStateException("The API documentation is not packaged: " + location, e);
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 is unavailable", e);
      }
    }

    ResponseEntity<Resource> response() {
      return ResponseEntity.ok()
          .contentType(type)
          .cacheControl(CacheControl.noCache())
          .eTag(entityTag)
          .body(resource);
    }
  }
}
