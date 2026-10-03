package com.bondplatform.dataprocessing.outbox.adapter.config;

import com.bondplatform.dataprocessing.outbox.adapter.persistence.JdbcOutboxEventStore;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.outbox.application.OutboxEventStore;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The outbox store every writer uses: it records events and, once the transaction that recorded
 * them commits, has the dispatcher try to deliver them at once (LLD section 23.4).
 *
 * <p>The attempt runs in the same invocation, after the commit and before the caller returns. It
 * never fails the caller; whatever it does not deliver, the sweep does. An event recorded outside a
 * transaction is left to the sweep.
 *
 * <p>The attempt runs in the transaction's after-commit callback, while its connection is still
 * bound to the thread (a Lambda has one connection, so a second transaction is not possible). The
 * dispatcher's reads and updates therefore run on that connection outside the committed
 * transaction, and are committed when the transaction manager restores auto-commit as it releases
 * the connection. Were that ever to change, a delivered event would stay pending and the sweep
 * would send it again, which queue deduplication absorbs.
 *
 * <p>Time budget: each send can take up to the SQS client's 10-second call timeout, on top of the
 * admission lock's 5 seconds, inside API Gateway's 29 seconds for one event per request. Revisit
 * this before a request records more events or a timeout grows.
 */
@Component
@Primary
public class DeliveringOutboxEventStore implements OutboxEventStore {

  private final JdbcOutboxEventStore store;
  private final OutboxDispatcher dispatcher;

  /** Creates the store. */
  public DeliveringOutboxEventStore(JdbcOutboxEventStore store, OutboxDispatcher dispatcher) {
    this.store = store;
    this.dispatcher = dispatcher;
  }

  @Override
  public void append(NewOutboxEvent event) {
    store.append(event);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      committedEvents().add(event.id());
    }
  }

  /** Returns this transaction's list of recorded events, registering delivery on first use. */
  private List<UUID> committedEvents() {
    Object existing = TransactionSynchronizationManager.getResource(this);
    if (existing instanceof AfterCommit afterCommit) {
      return afterCommit.eventIds;
    }
    AfterCommit afterCommit = new AfterCommit();
    TransactionSynchronizationManager.bindResource(this, afterCommit);
    TransactionSynchronizationManager.registerSynchronization(afterCommit);
    return afterCommit.eventIds;
  }

  /** Delivers one transaction's events after it commits, and forgets them when it ends. */
  private final class AfterCommit implements TransactionSynchronization {

    private final List<UUID> eventIds = new ArrayList<>();

    @Override
    public void afterCommit() {
      dispatcher.deliverCommitted(List.copyOf(eventIds));
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(DeliveringOutboxEventStore.this);
    }
  }
}
