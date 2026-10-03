package com.bondplatform.dataprocessing.outbox.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bondplatform.dataprocessing.outbox.adapter.persistence.JdbcOutboxEventStore;
import com.bondplatform.dataprocessing.outbox.application.OutboxDispatcher;
import com.bondplatform.dataprocessing.outbox.domain.NewOutboxEvent;
import com.bondplatform.dataprocessing.outbox.domain.OutboxDestination;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DeliveringOutboxEventStoreTest {

  private final JdbcOutboxEventStore jdbcStore = mock(JdbcOutboxEventStore.class);
  private final OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
  private final DeliveringOutboxEventStore store =
      new DeliveringOutboxEventStore(jdbcStore, dispatcher);

  @AfterEach
  void endTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.unbindResourceIfPossible(store);
  }

  @Test
  void eventsOfOneTransactionAreDeliveredTogetherAfterItCommits() {
    TransactionSynchronizationManager.initSynchronization();
    NewOutboxEvent first = event();
    NewOutboxEvent second = event();

    store.append(first);
    store.append(second);

    verify(jdbcStore).append(first);
    verify(jdbcStore).append(second);
    verifyNoInteractions(dispatcher);
    List<TransactionSynchronization> registered =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(registered).hasSize(1);
    registered.get(0).afterCommit();
    verify(dispatcher).deliverCommitted(List.of(first.id(), second.id()));
  }

  @Test
  void transactionThatEndsForgetsItsEvents() {
    TransactionSynchronizationManager.initSynchronization();
    store.append(event());

    TransactionSynchronizationManager.getSynchronizations()
        .get(0)
        .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    assertThat(TransactionSynchronizationManager.hasResource(store)).isFalse();
    verifyNoInteractions(dispatcher);
  }

  @Test
  void eventRecordedOutsideTransactionIsLeftToTheSweep() {
    NewOutboxEvent event = event();

    store.append(event);

    verify(jdbcStore).append(event);
    verifyNoInteractions(dispatcher);
    assertThat(TransactionSynchronizationManager.hasResource(store)).isFalse();
  }

  private static NewOutboxEvent event() {
    return new NewOutboxEvent(
        UUID.randomUUID(), OutboxDestination.FILE_PROCESSING, "g", 1L, "{}", Instant.EPOCH);
  }
}
