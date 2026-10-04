package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
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

  /** U-OUT-01. */
  @Test
  void statusFollowsAcceptedAndQuarantinedRows() {
    assertThat(new RowCounts(2, 2, 0, 0).status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(new RowCounts(3, 1, 1, 1).status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(new RowCounts(2, 1, 0, 1).status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(new RowCounts(2, 0, 2, 0).status()).isEqualTo(JobStatus.FAILED);
    assertThat(RowCounts.NONE.status()).isEqualTo(JobStatus.FAILED);
  }

  @Test
  void outcomeCarriesStatusCountsAndErrors() {
    JobOutcome outcome = new RowCounts(5, 3, 1, 1).outcome(4);

    assertThat(outcome)
        .isEqualTo(
            new JobOutcome(
                JobStatus.COMPLETED_WITH_ERRORS,
                "{\"sourceRecords\":5,\"acceptedRows\":3,\"invalidRows\":1,\"supersededRows\":1}",
                4));
    assertThatThrownBy(() -> new RowCounts(1, 1, 0, 0).outcome(Long.MAX_VALUE))
        .isInstanceOf(ArithmeticException.class);
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
