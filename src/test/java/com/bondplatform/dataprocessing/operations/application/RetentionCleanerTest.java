package com.bondplatform.dataprocessing.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RetentionCleanerTest {

  private static final Instant NOW = Instant.parse("2026-03-01T02:00:00Z");
  private static final int FULL = RetentionCleaner.BATCH_SIZE;

  private final RetentionStore store = mock(RetentionStore.class);
  private final RetentionCleaner cleaner =
      new RetentionCleaner(store, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void deletesReviewDataAfterOneCalendarYearAndDeliveredEventsAfterThirtyDays() {
    RetentionResult result = cleaner.clean();

    Instant yearAgo = Instant.parse("2025-03-01T02:00:00Z");
    verify(store).deleteIssuesCreatedBefore(yearAgo, FULL);
    verify(store).deleteRejectedRecordsCreatedBefore(yearAgo, FULL);
    verify(store).deleteDeliveredOutboxEventsBefore(Instant.parse("2026-01-30T02:00:00Z"), FULL);
    assertThat(result).isEqualTo(new RetentionResult(0, 0, 0));
  }

  @Test
  void deletesIssuesBeforeTheRecordsTheyBelongTo() {
    cleaner.clean();

    InOrder order = inOrder(store);
    order.verify(store).deleteIssuesCreatedBefore(any(), anyInt());
    order.verify(store).deleteRejectedRecordsCreatedBefore(any(), anyInt());
    order.verify(store).deleteDeliveredOutboxEventsBefore(any(), anyInt());
  }

  @Test
  void repeatsEachDeleteUntilBatchComesBackShort() {
    when(store.deleteIssuesCreatedBefore(any(), anyInt())).thenReturn(FULL, FULL, 7);
    when(store.deleteRejectedRecordsCreatedBefore(any(), anyInt())).thenReturn(FULL, 0);
    when(store.deleteDeliveredOutboxEventsBefore(any(), anyInt())).thenReturn(3);

    RetentionResult result = cleaner.clean();

    assertThat(result).isEqualTo(new RetentionResult(2L * FULL + 7, FULL, 3));
    verify(store, times(3)).deleteIssuesCreatedBefore(any(), anyInt());
    verify(store, times(2)).deleteRejectedRecordsCreatedBefore(any(), anyInt());
    verify(store, times(1)).deleteDeliveredOutboxEventsBefore(any(), anyInt());
  }
}
