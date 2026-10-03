package com.bondplatform.dataprocessing.outbox.adapter.config;

import com.bondplatform.dataprocessing.outbox.adapter.aws.SqsQueuePublisher;
import com.bondplatform.dataprocessing.outbox.application.OutboxDeliveryStore;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.outbox.application.QueuePublisher;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Wires delivery of outbox events to SQS. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueueProperties.class)
public class OutboxConfiguration {

  /**
   * Gives up on one send, retries included, after this long. It stays far below SQS's five-minute
   * deduplication window, so a late retry of a send cannot slip past deduplication, and below the
   * sweeper's one-minute run.
   */
  static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(10);

  static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(4);

  /**
   * Returns the SQS client. It resolves credentials on first use, not at startup, and has an
   * explicit timeout on every call.
   */
  @Bean(destroyMethod = "close")
  public SqsClient sqsClient(QueueProperties queues) {
    SqsClientBuilder builder =
        SqsClient.builder()
            .region(Region.of(queues.region()))
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(ATTEMPT_TIMEOUT))
            .overrideConfiguration(
                config ->
                    config.apiCallTimeout(API_CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT));
    if (queues.endpoint() != null) {
      builder.endpointOverride(queues.endpoint());
    }
    return builder.build();
  }

  /** Returns the publisher for both destinations. */
  @Bean
  public QueuePublisher queuePublisher(SqsClient sqs, QueueProperties queues) {
    return new SqsQueuePublisher(
        sqs,
        Map.of(
            OutboxDestination.FILE_PROCESSING, queues.fileProcessingUrl(),
            OutboxDestination.SECURITY_DETAILS, queues.securityDetailsUrl()));
  }

  /** Returns the dispatcher. */
  @Bean
  public OutboxDispatcher outboxDispatcher(
      OutboxDeliveryStore store, QueuePublisher publisher, Clock clock) {
    return new OutboxDispatcher(store, publisher, clock);
  }
}
