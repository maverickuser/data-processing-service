package com.bondplatform.dataprocessing.outbox.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.outbox.adapter.aws.SqsQueuePublisher;
import com.bondplatform.dataprocessing.outbox.application.OutboxDeliveryStore;
import java.net.URI;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

class OutboxConfigurationTest {

  private final OutboxConfiguration configuration = new OutboxConfiguration();

  @Test
  void buildsClientForTheRegionWithOrWithoutAnEndpointOverride() {
    try (SqsClient aws = configuration.sqsClient(queues(null));
        SqsClient local = configuration.sqsClient(queues(URI.create("http://127.0.0.1:9324")))) {
      assertThat(aws.serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTH_1);
      assertThat(aws.serviceClientConfiguration().endpointOverride()).isEmpty();
      assertThat(local.serviceClientConfiguration().endpointOverride())
          .contains(URI.create("http://127.0.0.1:9324"));
      assertThat(aws.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
          .contains(OutboxConfiguration.API_CALL_TIMEOUT);
    }
  }

  @Test
  void wiresPublisherAndDispatcher() {
    SqsClient sqs = mock(SqsClient.class);

    assertThat(configuration.queuePublisher(sqs, queues(null)))
        .isInstanceOf(SqsQueuePublisher.class);
    assertThat(
            configuration.outboxDispatcher(
                mock(OutboxDeliveryStore.class),
                configuration.queuePublisher(sqs, queues(null)),
                Clock.systemUTC()))
        .isNotNull();
  }

  private static QueueProperties queues(@Nullable URI endpoint) {
    return new QueueProperties(
        "ap-south-1",
        endpoint,
        URI.create("https://sqs.ap-south-1.amazonaws.com/1/jobs.fifo"),
        URI.create("https://sqs.ap-south-1.amazonaws.com/2/details"));
  }
}
