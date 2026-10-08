package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.internal.LambdaContainerHandler;
import com.amazonaws.serverless.proxy.model.HttpApiV2AuthorizerMap;
import com.amazonaws.serverless.proxy.model.HttpApiV2IamAuthorizer;
import com.amazonaws.serverless.proxy.model.HttpApiV2JwtAuthorizer;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class ReadCallerFilterTest {

  private static final String SMOKE_ROLE = "arn:aws:iam::123456789012:role/smoke-test";
  private static final String SMOKE_SESSION =
      "arn:aws:sts::123456789012:assumed-role/smoke-test/github-actions";

  private final List<Exception> resolved = new ArrayList<>();
  private final HandlerExceptionResolver resolver =
      (request, response, handler, exception) -> {
        resolved.add(exception);
        return new ModelAndView();
      };

  @Test
  void allowedRoleIsLetThrough() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter(SMOKE_ROLE).doFilter(read(iam(SMOKE_SESSION)), new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(resolved).isEmpty();
  }

  @Test
  void allowedRoleSessionMayContainComma() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter(SMOKE_ROLE)
        .doFilter(
            read(iam("arn:aws:sts::123456789012:assumed-role/smoke-test/session,one")),
            new MockHttpServletResponse(),
            chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(resolved).isEmpty();
  }

  @Test
  void roleWithPathMatchesItsSessionAndNameIgnoresCase() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter("arn:aws:iam::123456789012:role/ci/Smoke-Test")
        .doFilter(read(iam(SMOKE_SESSION)), new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "arn:aws:sts::123456789012:assumed-role/other-role/session",
        "arn:aws:sts::210987654321:assumed-role/smoke-test/session",
        "arn:aws-cn:sts::123456789012:assumed-role/smoke-test/session",
        "arn:aws:iam::123456789012:user/smoke-test",
        "arn:aws:iam::123456789012:root",
        "arn:aws:iam::123456789012:role/smoke-test",
        "arn:aws:sts::123456789012:assumed-role/smoke-test/session/extra",
        "arn:aws:sts::123456789012:assumed-role/smoke-test/session\nforged",
        "arn:aws:sts::123456789012:assumed-role/smoke-test,x/session",
        ""
      })
  void anyOtherCallerIsRefused(String callerArn) throws Exception {
    assertRefused(filter(SMOKE_ROLE), read(iam(callerArn)));
  }

  @Test
  void requestWithoutApiGatewayContextIsRefused() throws Exception {
    assertRefused(filter(SMOKE_ROLE), read(null));
  }

  @Test
  void requestWithoutIamAuthorizerIsRefused() throws Exception {
    HttpApiV2ProxyRequestContext context = new HttpApiV2ProxyRequestContext();
    assertRefused(filter(SMOKE_ROLE), read(context));

    HttpApiV2AuthorizerMap jwt = new HttpApiV2AuthorizerMap();
    jwt.putJwtAuthorizer(new HttpApiV2JwtAuthorizer());
    context.setAuthorizer(jwt);
    assertRefused(filter(SMOKE_ROLE), read(context));
  }

  @Test
  void emptyListRefusesEveryone() throws Exception {
    assertRefused(
        new ReadCallerFilter(new ReadCallerProperties(null), resolver), read(iam(SMOKE_SESSION)));
  }

  @Test
  void otherMethodsOnReadPathsAreChecked() throws Exception {
    MockHttpServletRequest options = read(null);
    options.setMethod("OPTIONS");
    assertRefused(filter(SMOKE_ROLE), options);

    MockHttpServletRequest post = read(null);
    post.setMethod("POST");
    assertRefused(filter(SMOKE_ROLE), post);
  }

  @Test
  void submissionsAreNotChecked() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter(SMOKE_ROLE)
        .doFilter(
            new MockHttpServletRequest("POST", "/v1/event-ingestions"),
            new MockHttpServletResponse(),
            chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void callerIsReadFromRealHttpApiEvent() throws Exception {
    String event =
        """
        {"version":"2.0","routeKey":"GET /v1/securities/{isin}","rawPath":"/v1/securities/X",
         "requestContext":{"http":{"method":"GET","path":"/v1/securities/X"},
          "authorizer":{"iam":{"accessKey":"ASIAEXAMPLE","accountId":"123456789012",
           "callerId":"AROAEXAMPLE:github-actions",
           "userArn":"arn:aws:sts::123456789012:assumed-role/smoke-test/github-actions",
           "userId":"AROAEXAMPLE:github-actions"}}}}
        """;
    HttpApiV2ProxyRequest request =
        LambdaContainerHandler.getObjectMapper().readValue(event, HttpApiV2ProxyRequest.class);
    MockFilterChain chain = new MockFilterChain();

    filter(SMOKE_ROLE)
        .doFilter(read(request.getRequestContext()), new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void entryThatIsNotRoleArnStopsTheApplication() {
    assertThatThrownBy(() -> new ReadCallerProperties(List.of("arn:aws:iam::123456789012:user/x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReadCallerProperties(
                    List.of("arn:aws:iam::123456789012:role/a,arn:aws:iam::123456789012:role/b")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadCallerProperties(List.of("*")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReadCallerProperties(List.of("arn:aws:iam::12345:role/x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ReadCallerFilter filter(String... roleArns) {
    return new ReadCallerFilter(new ReadCallerProperties(List.of(roleArns)), resolver);
  }

  private void assertRefused(ReadCallerFilter filter, MockHttpServletRequest request)
      throws Exception {
    resolved.clear();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(resolved)
        .singleElement()
        .isInstanceOfSatisfying(
            ApiProblemException.class,
            problem -> {
              assertThat(problem.type()).isEqualTo(ProblemType.FORBIDDEN);
              assertThat(problem.getMessage()).isEqualTo(ReadCallerFilter.NOT_ALLOWED);
            });
  }

  private static MockHttpServletRequest read(@Nullable HttpApiV2ProxyRequestContext context) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/securities/X");
    if (context != null) {
      request.setAttribute(RequestReader.HTTP_API_CONTEXT_PROPERTY, context);
    }
    return request;
  }

  private static HttpApiV2ProxyRequestContext iam(String userArn) {
    HttpApiV2IamAuthorizer caller = new HttpApiV2IamAuthorizer();
    caller.setUserArn(userArn);
    HttpApiV2AuthorizerMap authorizer = new HttpApiV2AuthorizerMap();
    authorizer.putIamAuthorizer(caller);
    HttpApiV2ProxyRequestContext context = new HttpApiV2ProxyRequestContext();
    context.setAuthorizer(authorizer);
    return context;
  }
}
