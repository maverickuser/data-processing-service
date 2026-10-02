package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lambda entry point for API Gateway HTTP API requests.
 *
 * <p>Passes each request to the Spring web layer and writes its response back. The application
 * context is started once, when Lambda initializes the function, and reused by every invocation.
 */
public class ApiGatewayHandler implements RequestStreamHandler {

  private final HttpEventServer server;

  /** Used by Lambda: starts the application context. */
  public ApiGatewayHandler() {
    this(startApplication());
  }

  /** Creates a handler that serves events with the given server. */
  ApiGatewayHandler(HttpEventServer server) {
    this.server = server;
  }

  @Override
  public void handleRequest(InputStream event, OutputStream response, Context context)
      throws IOException {
    server.serve(event, response, context);
  }

  private static HttpEventServer startApplication() {
    try {
      return SpringBootLambdaContainerHandler.getHttpApiV2ProxyHandler(
              DataProcessingApplication.class)
          ::proxyStream;
    } catch (ContainerInitializationException e) {
      throw new IllegalStateException("The application context failed to start", e);
    }
  }

  /** Serves one API Gateway HTTP API event, reading it from and writing the result to streams. */
  @FunctionalInterface
  interface HttpEventServer {

    /** Reads the event, handles it, and writes the API Gateway response. */
    void serve(InputStream event, OutputStream response, Context context) throws IOException;
  }
}
