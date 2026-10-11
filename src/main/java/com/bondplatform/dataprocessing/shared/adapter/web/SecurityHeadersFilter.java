package com.bondplatform.dataprocessing.shared.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks every response, problems included, as data that a browser must never run or frame. The API
 * returns only JSON, some of it copied from source files, so a browser must not guess another media
 * type ({@code nosniff}), and the response may load nothing and appear in no frame.
 *
 * <p>The documentation pages under {@code /docs} are the one exception (LLD 23.2): Swagger UI may
 * load its own script, style sheet, and document from this origin, and call the API it documents.
 * They still load nothing from elsewhere and appear in no frame.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

  static final String NO_SNIFF = "nosniff";
  static final String NOTHING_LOADED = "default-src 'none'; frame-ancestors 'none'";
  static final String DOCUMENTATION_PAGE =
      "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; frame-ancestors 'none'";
  private static final String DOCUMENTATION = "/docs";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", NO_SNIFF);
    response.setHeader(
        "Content-Security-Policy", isDocumentation(request) ? DOCUMENTATION_PAGE : NOTHING_LOADED);
    chain.doFilter(request, response);
  }

  private static boolean isDocumentation(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals(DOCUMENTATION) || path.startsWith(DOCUMENTATION + "/");
  }
}
