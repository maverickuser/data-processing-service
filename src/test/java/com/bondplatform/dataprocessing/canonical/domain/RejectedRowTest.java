package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Listing and numbering a quarantined row's issues. */
class RejectedRowTest {

  private static final List<CanonicalRow> ROWS = GoldenBhavcopy.rows();

  @Test
  void supersededRowHasOneRowLevelIssueAndItsIsin() {
    RejectedRow rejected = RejectedRow.of(ROWS.get(1), "isin", 7);

    assertThat(rejected.isin()).isEqualTo("INE002B08CD2");
    assertThat(rejected.issues()).hasSize(1);
    RejectedRow.ReviewIssue issue = rejected.issues().get(0);
    assertThat(issue.sequenceNumber()).isEqualTo(7);
    assertThat(issue.issue().code()).isEqualTo(ErrorCode.DUPLICATE_ISIN_SUPERSEDED);
    assertThat(issue.field()).isNull();
  }

  @Test
  void invalidRowListsFieldIssuesWithTheirFieldAndHasNoIsinWhenBlank() {
    RejectedRow rejected = RejectedRow.of(ROWS.get(5), "isin", 1);

    assertThat(rejected.isin()).isNull();
    RejectedRow.ReviewIssue first = rejected.issues().get(0);
    assertThat(first.sequenceNumber()).isEqualTo(1);
    assertThat(first.issue().code()).isEqualTo(ErrorCode.REQUIRED_VALUE_MISSING);
    assertThat(first.field())
        .extracting(CanonicalField::name, CanonicalField::rawValue)
        .containsExactly("isin", " ");
  }

  @Test
  void issuesAreNumberedConsecutively() {
    for (CanonicalRow row : ROWS) {
      if (row.disposition() == Disposition.ACCEPTED) {
        continue;
      }
      List<RejectedRow.ReviewIssue> issues = RejectedRow.of(row, "isin", 10).issues();
      assertThat(issues).isNotEmpty();
      for (int i = 0; i < issues.size(); i++) {
        assertThat(issues.get(i).sequenceNumber()).isEqualTo(10L + i);
      }
    }
  }

  @Test
  void acceptedRowIsNotRejected() {
    assertThatThrownBy(() -> RejectedRow.of(ROWS.get(0), "isin", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Record 2 is accepted");
  }
}
