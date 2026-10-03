package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class RequestCharsetFilterTest {

  private final List<Exception> resolved = new ArrayList<>();
  private final List<HttpServletRequest> resolvedRequests = new ArrayList<>();
  private final HandlerExceptionResolver resolver =
      (request, response, handler, exception) -> {
        resolved.add(exception);
        resolvedRequests.add(request);
        return new ModelAndView();
      };
  private final RequestCharsetFilter filter = new RequestCharsetFilter(resolver);

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "application/cloudevents+json",
        "application/cloudevents+json; charset=utf-8",
        "application/cloudevents+json;charset=UTF-8",
        "application/cloudevents+json; Charset=\"utf8\"",
        "application/json; version=1; charset=utf-8",
        "text/plain"
      })
  void noCharacterSetOrUtf8IsAccepted(String contentType) {
    assertThat(RequestCharsetFilter.declaresOnlyUtf8(contentType)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/cloudevents+json; charset=ISO-8859-1",
        "application/cloudevents+json; charset=UTF-16",
        "application/cloudevents+json; charset=foo",
        "application/cloudevents+json; charset=\"x y\"",
        "application/cloudevents+json; charset=",
        "application/cloudevents+json; charset",
        "application/cloudevents+json; charset=utf-8; charset=latin1"
      })
  void anyOtherCharacterSetIsRefused(String contentType) {
    assertThat(RequestCharsetFilter.declaresOnlyUtf8(contentType)).isFalse();
  }

  @Test
  void acceptedRequestIsReadAsUtf8AndPassedOn() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/event-ingestions");
    request.setContentType("application/cloudevents+json");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(request.getCharacterEncoding()).isEqualTo("UTF-8");
    assertThat(resolved).isEmpty();
  }

  @Test
  void refusedRequestBecomesUnsupportedMediaTypeProblemAndGoesNoFurther() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/event-ingestions");
    request.addHeader("Content-Type", "application/cloudevents+json; charset=\"x y\"");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(resolved)
        .singleElement()
        .isInstanceOfSatisfying(
            ApiProblemException.class,
            problem -> assertThat(problem.type()).isEqualTo(ProblemType.UNSUPPORTED_MEDIA_TYPE));
    HttpServletRequest seen = resolvedRequests.get(0);
    assertThat(seen.getContentType()).isNull();
    assertThat(seen.getCharacterEncoding()).isNull();
    assertThat(seen.getHeader("content-type")).isNull();
    assertThat(seen.getHeaders("Content-Type").hasMoreElements()).isFalse();
    assertThat(Collections.list(seen.getHeaderNames())).doesNotContain("Content-Type");
    assertThat(seen.getMethod()).isEqualTo("POST");
  }
}
