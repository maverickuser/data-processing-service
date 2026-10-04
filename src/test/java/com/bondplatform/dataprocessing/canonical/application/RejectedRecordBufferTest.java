package com.bondplatform.dataprocessing.canonical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bondplatform.dataprocessing.canonical.domain.CanonicalRow;
import com.bondplatform.dataprocessing.canonical.domain.GoldenBhavcopy;
import com.bondplatform.dataprocessing.canonical.domain.RejectedRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Collecting quarantined rows into bounded batches. */
class RejectedRecordBufferTest {

  private static final List<CanonicalRow> ROWS = GoldenBhavcopy.rows();
  private static final CanonicalRow ACCEPTED = ROWS.get(0);
  private static final CanonicalRow SUPERSEDED = ROWS.get(1);
  private static final UUID RUN_ID = new UUID(0, 9);

  private final RejectedRecordStore store = mock(RejectedRecordStore.class);
  private final RejectedRecordBuffer buffer =
      new RejectedRecordBuffer(store, GoldenBhavcopy.RUN, RUN_ID, "isin");

  @Test
  void storesOnlyQuarantinedRowsInFileOrderOnFlush() {
    ROWS.forEach(buffer::add);

    buffer.flush();

    List<RejectedRow> stored = storedBatches(1).get(0);
    assertThat(stored)
        .extracting(rejected -> rejected.row().record().recordNumber())
        .containsExactly(3L, 4L, 7L, 8L, 9L);
    assertThat(buffer.issueCount())
        .isEqualTo(stored.stream().mapToLong(rejected -> rejected.issues().size()).sum());
    assertThat(stored.get(stored.size() - 1).issues().getLast().sequenceNumber())
        .isEqualTo(buffer.issueCount());
  }

  @Test
  void storesFullBatchesAsTheyFillAndNumbersIssuesAcrossThem() {
    for (int i = 0; i < 2 * RejectedRecordBuffer.BATCH_SIZE + 1; i++) {
      buffer.add(SUPERSEDED);
    }
    verify(store, times(2)).saveAll(eq(GoldenBhavcopy.RUN), eq(RUN_ID), any());

    buffer.flush();

    List<List<RejectedRow>> batches = storedBatches(3);
    assertThat(batches).extracting(List::size).containsExactly(500, 500, 1);
    assertThat(batches.get(1).get(0).issues().get(0).sequenceNumber()).isEqualTo(501);
    assertThat(batches.get(2).get(0).issues().get(0).sequenceNumber()).isEqualTo(1001);
    assertThat(buffer.issueCount()).isEqualTo(1001);
  }

  @Test
  void flushWithNothingHeldStoresNothing() {
    buffer.add(ACCEPTED);

    buffer.flush();

    verify(store, never()).saveAll(any(), any(), any());
    assertThat(buffer.issueCount()).isZero();
  }

  @SuppressWarnings("unchecked")
  private List<List<RejectedRow>> storedBatches(int count) {
    ArgumentCaptor<List<RejectedRow>> batches = ArgumentCaptor.forClass(List.class);
    verify(store, times(count)).saveAll(eq(GoldenBhavcopy.RUN), eq(RUN_ID), batches.capture());
    return batches.getAllValues();
  }
}
