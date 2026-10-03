package com.bondplatform.dataprocessing.outbox.application;

import com.bondplatform.dataprocessing.outbox.domain.OutboxEvent;

/** Sends one outbox event to its destination queue. */
public interface QueuePublisher {

  /**
   * Sends the event, using its ID as the queue's deduplication ID, so that sending the same event
   * twice delivers it once.
   *
   * @throws QueuePublishException if the queue did not accept the event; it may be sent again
   */
  void publish(OutboxEvent event);
}
