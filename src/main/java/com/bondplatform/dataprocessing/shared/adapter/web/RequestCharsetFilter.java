package com.bondplatform.dataprocessing.shared.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Reads every request body as UTF-8, and refuses a request that declares any other character set
 * with {@code 415}.
 *
 * <p>It runs before anything else reads the request, and looks at the {@code charset} parameter of
 * the raw {@code Content-Type} text without asking Java to resolve the name: an unknown or
 * malformed name must be refused here, not fail inside the servlet container. Accepted are no
 * {@code charset} at all, {@code utf-8}, and {@code utf8}, in any case and optionally quoted. The
 * problem response is built by {@link GlobalExceptionHandler}, like every other. It exists only
 * where the application serves HTTP; the worker and scheduled functions start without a web server.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// After SecurityHeadersFilter, so its refusals carry the security headers too, and after
// ReadCallerFilter, so a read from a caller that is not allowed is refused first
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestCharsetFilter extends OncePerRequestFilter {

  private static final String CHARSET = "charset";
  private static final String NOT_UTF_8 = "Send the request body in UTF-8.";

  private final HandlerExceptionResolver problems;

  /** Creates the filter; refusals are passed to the given resolver to become problem responses. */
  public RequestCharsetFilter(
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems) {
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!declaresOnlyUtf8(request.getHeader(HttpHeaders.CONTENT_TYPE))) {
      // The framework reads the content type again while writing the problem; it must not see a
      // character-set name that cannot be parsed.
      problems.resolveException(
          new WithoutContentType(request),
          response,
          null,
          new ApiProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, NOT_UTF_8));
      return;
    }
    request.setCharacterEncoding(StandardCharsets.UTF_8.name());
    chain.doFilter(request, response);
  }

  /** Tells whether the content type declares no character set, or only UTF-8. */
  static boolean declaresOnlyUtf8(@Nullable String contentType) {
    if (contentType == null) {
      return true;
    }
    String[] parts = contentType.split(";", -1);
    for (int index = 1; index < parts.length; index++) {
      String parameter = parts[index].strip();
      int equals = parameter.indexOf('=');
      String name = equals < 0 ? parameter : parameter.substring(0, equals).strip();
      if (!name.equalsIgnoreCase(CHARSET)) {
        continue;
      }
      String value = equals < 0 ? "" : unquoted(parameter.substring(equals + 1).strip());
      String normalized = value.toLowerCase(Locale.ROOT);
      if (!normalized.equals("utf-8") && !normalized.equals("utf8")) {
        return false;
      }
    }
    return true;
  }

  private static String unquoted(String value) {
    boolean quoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    return quoted ? value.substring(1, value.length() - 1) : value;
  }

  /** The request as it is, except that it declares no content type or character set. */
  private static final class WithoutContentType extends HttpServletRequestWrapper {

    WithoutContentType(HttpServletRequest request) {
      super(request);
    }

    @Override
    public @Nullable String getContentType() {
      return null;
    }

    @Override
    public @Nullable String getCharacterEncoding() {
      return null;
    }

    @Override
    public @Nullable String getHeader(String name) {
      return isContentType(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return isContentType(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      return Collections.enumeration(
          Collections.list(super.getHeaderNames()).stream()
              .filter(name -> !isContentType(name))
              .toList());
    }

    private static boolean isContentType(String name) {
      return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name);
    }
  }
}
