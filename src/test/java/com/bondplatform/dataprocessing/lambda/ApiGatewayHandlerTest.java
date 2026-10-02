package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ApiGatewayHandlerTest {

  @Test
  void passesTheEventToTheProxyAndReturnsItsResponse() throws IOException {
    ApiGatewayHandler handler =
        new ApiGatewayHandler(
            (event, response, context) -> {
              response.write("handled:".getBytes(StandardCharsets.UTF_8));
              event.transferTo(response);
            });
    ByteArrayOutputStream response = new ByteArrayOutputStream();

    handler.handleRequest(
        new ByteArrayInputStream("event".getBytes(StandardCharsets.UTF_8)),
        response,
        new FixedLambdaContext());

    assertThat(response.toString(StandardCharsets.UTF_8)).isEqualTo("handled:event");
  }
}
