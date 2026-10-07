package com.bondplatform.dataprocessing.review.adapter.web;

import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.model.HttpApiV2AuthorizerMap;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Lets a read request through only when API Gateway signed it in as one of the allowed IAM roles
 * (LLD section 23.2), and refuses every other with {@code 403}.
 *
 * <p>API Gateway checks the SigV4 signature and the caller's {@code execute-api:Invoke} permission,
 * then passes the caller in the request context; without that context, as for a request that did
 * not come through API Gateway, nobody is allowed. Only {@code GET} and {@code HEAD} requests are
 * checked, so an application serving both kinds of route, as in tests, still admits submissions.
 * The refusal says nothing about why, and the log names only the caller's role.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("data-processing.api.read-routes")
@EnableConfigurationProperties(ReadCallerProperties.class)
// After SecurityHeadersFilter, so refusals carry the security headers, and before anything reads
// the request
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ReadCallerFilter extends OncePerRequestFilter {

  static final String NOT_ALLOWED = "This caller may not read from this API.";

  private static final Logger LOG = LoggerFactory.getLogger(ReadCallerFilter.class);

  private final Set<String> allowedRoles;
  private final HandlerExceptionResolver problems;

  /** Creates the filter; refusals are passed to the given resolver to become problem responses. */
  public ReadCallerFilter(
      ReadCallerProperties callers,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems) {
    this.allowedRoles = callers.allowedRoles();
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    return !"GET".equals(method) && !"HEAD".equals(method);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String role = ReadCallerProperties.roleOf(callerArn(request));
    if (role == null || !allowedRoles.contains(role)) {
      LOG.warn("Read request refused, callerRole={}", role == null ? "none" : role);
      problems.resolveException(
          request, response, null, new ApiProblemException(ProblemType.FORBIDDEN, NOT_ALLOWED));
      return;
    }
    chain.doFilter(request, response);
  }

  /** Returns the IAM caller API Gateway put in the request context, if there is one. */
  private static @Nullable String callerArn(HttpServletRequest request) {
    if (!(request.getAttribute(RequestReader.HTTP_API_CONTEXT_PROPERTY)
        instanceof HttpApiV2ProxyRequestContext context)) {
      return null;
    }
    HttpApiV2AuthorizerMap authorizer = context.getAuthorizer();
    if (authorizer == null || !authorizer.isIam() || authorizer.getIamAuthorizer() == null) {
      return null;
    }
    return authorizer.getIamAuthorizer().getUserArn();
  }
}
