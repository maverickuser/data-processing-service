package com.bondplatform.dataprocessing.review.adapter.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.shared.adapter.web.GlobalExceptionHandler;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemDetailFactory;
import com.bondplatform.dataprocessing.shared.adapter.web.SecurityHeadersFilter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * A refused read gets the documented problem body from the real exception resolver, with the
 * security headers, and never reaches the controller.
 */
class ReadCallerProblemTest {

  @Test
  void refusedReadIsForbiddenProblemWithSecurityHeaders() throws Exception {
    GetSecurity getSecurity = mock(GetSecurity.class);
    AtomicReference<HandlerExceptionResolver> resolver = new AtomicReference<>();
    ReadCallerFilter filter =
        new ReadCallerFilter(
            new ReadCallerProperties(List.of("arn:aws:iam::123456789012:role/smoke-test")),
            (request, response, handler, exception) ->
                Objects.requireNonNull(resolver.get())
                    .resolveException(request, response, handler, exception));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new SecurityController(getSecurity))
            .setControllerAdvice(
                new GlobalExceptionHandler(new ProblemDetailFactory(UUID::randomUUID)))
            .addFilters(new SecurityHeadersFilter(), filter)
            .build();
    resolver.set(
        Objects.requireNonNull(mvc.getDispatcherServlet().getWebApplicationContext())
            .getBean("handlerExceptionResolver", HandlerExceptionResolver.class));

    mvc.perform(get("/v1/securities/INE0KH208019").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.type").value("urn:bond-platform:problem:forbidden"))
        .andExpect(jsonPath("$.detail").value(ReadCallerFilter.NOT_ALLOWED))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    verifyNoInteractions(getSecurity);
  }
}
