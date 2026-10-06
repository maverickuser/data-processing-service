package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

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
}
