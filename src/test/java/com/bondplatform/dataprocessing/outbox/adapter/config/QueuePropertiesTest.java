package com.bondplatform.dataprocessing.outbox.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.net.URI;
import org.junit.jupiter.api.Test;

class QueuePropertiesTest {

  private static final URI JOBS = URI.create("https://sqs.ap-south-1.amazonaws.com/1/jobs.fifo");
  private static final URI DETAILS = URI.create("https://sqs.ap-south-1.amazonaws.com/2/details");

  @Test
  void givesEachDestinationItsQueue() {
    QueueProperties queues = new QueueProperties("ap-south-1", null, JOBS, DETAILS);

    assertThat(queues.urlOf(OutboxDestination.FILE_PROCESSING)).isEqualTo(JOBS);
    assertThat(queues.urlOf(OutboxDestination.SECURITY_DETAILS)).isEqualTo(DETAILS);
    assertThat(queues.endpoint()).isNull();
  }

  @Test
  void jobQueueMustBeFifo() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new QueueProperties(
                    "ap-south-1",
                    null,
                    URI.create("https://sqs.ap-south-1.amazonaws.com/1/jobs"),
                    DETAILS))
        .withMessageContaining(".fifo");
  }

  @Test
  void regionAndBothQueuesAreRequired() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new QueueProperties(" ", null, JOBS, DETAILS))
        .withMessageContaining("region");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new QueueProperties(null, null, JOBS, DETAILS))
        .withMessageContaining("region");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new QueueProperties("ap-south-1", null, null, DETAILS))
        .withMessageContaining("file-processing-url");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new QueueProperties("ap-south-1", null, JOBS, URI.create("")))
        .withMessageContaining("security-details-url");
  }
}
