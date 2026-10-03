package com.bondplatform.dataprocessing.outbox.adapter.aws;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * An SQS-compatible queue server for tests, started once per test run.
 *
 * <p>ElasticMQ implements the SQS API, FIFO queues and deduplication included, and needs no account
 * or token, which is why tests use it rather than LocalStack.
 */
public final class ElasticMq {

  private static final int PORT = 9324;
  private static final GenericContainer<?> CONTAINER = start();

  private ElasticMq() {}

  /** Returns the endpoint to use instead of AWS's. */
  public static URI endpoint() {
    return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT));
  }

  /** Returns a client for the server; the test JVM's fake credentials are accepted. */
  public static SqsClient client() {
    return SqsClient.builder().region(Region.AP_SOUTH_1).endpointOverride(endpoint()).build();
  }

  private static GenericContainer<?> start() {
    GenericContainer<?> container =
        new GenericContainer<>("softwaremill/elasticmq-native:1.7.1")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forListeningPort());
    container.start();
    return container;
  }
}
