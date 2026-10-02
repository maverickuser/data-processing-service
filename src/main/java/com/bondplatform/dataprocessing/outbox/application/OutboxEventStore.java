package com.bondplatform.dataprocessing.outbox.application;

import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;

/** Stores outbox events. */
public interface OutboxEventStore {

  /**
   * Records an event as pending delivery, due immediately.
   *
   * <p>Call this inside the transaction that makes the change the event announces. Nothing is sent
   * here; delivery happens after commit.
   */
  void append(NewOutboxEvent event);
}
