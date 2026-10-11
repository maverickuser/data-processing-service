package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

  @Test
  void everyResponseForbidsSniffingLoadingAndFraming() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new SecurityHeadersFilter()
        .doFilter(
            new MockHttpServletRequest("GET", "/v1/securities/INE0KH208019"), response, chain);

    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Content-Security-Policy"))
        .isEqualTo("default-src 'none'; frame-ancestors 'none'");
    assertThat(chain.getRequest()).isNotNull();
  }

  // U-REV-08
  @Test
  void onlyTheDocumentationPagesMayLoadTheirOwnScriptsAndStyles() throws Exception {
    for (String path : new String[] {"/docs", "/docs/swagger-ui-bundle.js"}) {
      assertThat(policyFor(path)).as(path).isEqualTo(SecurityHeadersFilter.DOCUMENTATION_PAGE);
    }
    for (String path : new String[] {"/docsx", "/v1/docs", "/"}) {
      assertThat(policyFor(path)).as(path).isEqualTo(SecurityHeadersFilter.NOTHING_LOADED);
    }
    assertThat(SecurityHeadersFilter.DOCUMENTATION_PAGE)
        .contains("default-src 'none'", "script-src 'self';", "frame-ancestors 'none'")
        .doesNotContain("unsafe-eval", "*", "http");
  }

  private static String policyFor(String path) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter()
        .doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
    return Objects.requireNonNull(response.getHeader("Content-Security-Policy"));
  }
}
