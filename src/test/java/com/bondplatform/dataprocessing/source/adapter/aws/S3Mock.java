package com.bondplatform.dataprocessing.source.adapter.aws;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** An S3-compatible server for tests (Adobe S3Mock), started once per test run. */
public final class S3Mock {

  private static final int PORT = 9090;
  private static final GenericContainer<?> CONTAINER = start();

  private S3Mock() {}

  /** Returns the endpoint to use instead of AWS's. */
  public static URI endpoint() {
    return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT));
  }

  /** Returns a path-style client for the server; the test JVM's fake credentials are accepted. */
  public static S3Client client() {
    return S3Client.builder()
        .region(Region.AP_SOUTH_1)
        .endpointOverride(endpoint())
        .forcePathStyle(true)
        .build();
  }

  private static GenericContainer<?> start() {
    GenericContainer<?> container =
        new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort());
    container.start();
    return container;
  }
}
