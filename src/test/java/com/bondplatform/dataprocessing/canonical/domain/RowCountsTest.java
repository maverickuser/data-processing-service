package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RowCountsTest {

  @Test
  void eachDispositionAddsOneSourceRecord() {
    RowCounts counts =
        RowCounts.NONE
            .plus(Disposition.ACCEPTED)
            .plus(Disposition.QUARANTINED_INVALID)
            .plus(Disposition.QUARANTINED_INVALID)
            .plus(Disposition.QUARANTINED_SUPERSEDED);

    assertThat(counts).isEqualTo(new RowCounts(4, 1, 2, 1));
  }

  @Test
  void dispositionsMustAddUpToSourceRecords() {
    assertThatThrownBy(() -> new RowCounts(3, 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Dispositions must add up to the source records: 1 + 1 + 0 != 3");
  }

  @Test
  void onlySupersededRowNamesWinner() {
    CanonicalRecord record =
        new CsvRowValidator(BhavcopyContract.CONTRACT, RuleRegistry.standard())
            .validate(BhavcopyContract.row(2, Map.of()));

    assertThatThrownBy(() -> new CanonicalRow(record, Disposition.ACCEPTED, OptionalLong.of(3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CanonicalRow(record, Disposition.QUARANTINED_SUPERSEDED, OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
