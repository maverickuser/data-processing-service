package com.bondplatform.dataprocessing.publication.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bondplatform.dataprocessing.job.application.JobCompletion;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.publication.application.DailyMarketSummaryRepository;
import com.bondplatform.dataprocessing.publication.application.SecurityRepository;
import com.bondplatform.dataprocessing.shared.supplier.IdSupplier;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PublicationPropertiesTest {

  @Test
  void sourceIsRequired() {
    assertThatThrownBy(() -> new PublicationProperties(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("data-processing.events.source is required");
    assertThatThrownBy(() -> new PublicationProperties(URI.create("")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceIsNotWebAddress() {
    assertThatThrownBy(
            () ->
                new PublicationProperties(
                    URI.create(
                        "https://sqs.ap-south-1.amazonaws.com/123456789012/security-details")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a URL");
    assertThatThrownBy(() -> new PublicationProperties(URI.create("http://example.com")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configurationCreatesPublisher() {
    PublicationProperties properties =
        new PublicationProperties(URI.create("urn:bond-platform:structured-file-processing:test"));

    assertThat(
            new PublicationConfiguration()
                .publishDailyMarketSummaries(
                    mock(SecurityRepository.class),
                    mock(DailyMarketSummaryRepository.class),
                    mock(JobCompletion.class),
                    mock(OutboxEventStore.class),
                    mock(IdSupplier.class),
                    Clock.systemUTC(),
                    properties))
        .isNotNull();
  }
}
