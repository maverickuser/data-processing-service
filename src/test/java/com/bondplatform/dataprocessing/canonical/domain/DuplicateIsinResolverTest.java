package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Test cases U-DUP-01..03. */
class DuplicateIsinResolverTest {

  private static final String ISIN = "INE121A07QY9";

  private final CsvRowValidator validator =
      new CsvRowValidator(BhavcopyContract.CONTRACT, RuleRegistry.standard());
  private final DuplicateIsinResolver resolver = new DuplicateIsinResolver("isin");

  // U-DUP-01
  @Test
  void lastValidRowWinsAndInvalidRowNeverDisplacesIt() {
    List<CanonicalRow> rows =
        resolveAll(valid(2, ISIN), valid(5, ISIN), record(9, Map.of("turnover", "-1")));

    assertThat(rows)
        .extracting(CanonicalRow::disposition)
        .containsExactly(
            Disposition.QUARANTINED_SUPERSEDED,
            Disposition.ACCEPTED,
            Disposition.QUARANTINED_INVALID);
    assertThat(rows.get(0).supersededBy()).isEqualTo(OptionalLong.of(5));
    assertThat(rows.get(0).rowErrors())
        .containsExactly(
            new ValidationIssue(
                ErrorCode.DUPLICATE_ISIN_SUPERSEDED,
                "Record 5 has the same ISIN and supersedes this record."));
    assertThat(rows.get(1).rowErrors()).isEmpty();
    assertThat(rows.get(2).supersededBy()).isEmpty();
  }

  // U-DUP-02
  @Test
  void isinWithOnlyInvalidRowsHasNoAcceptedRow() {
    List<CanonicalRow> rows =
        resolveAll(record(2, Map.of("traded_volume", "1.5")), record(3, Map.of("high_price", "1")));

    assertThat(rows)
        .extracting(CanonicalRow::disposition)
        .containsOnly(Disposition.QUARANTINED_INVALID);
  }

  // U-DUP-03
  @Test
  void isinsDifferingOnlyByCaseOrSpacesAreTheSameIsin() {
    List<CanonicalRow> rows =
        resolveAll(valid(2, " ine121a07qy9"), valid(3, "Ine121A07QY9 "), valid(4, "INE002A08534"));

    assertThat(rows)
        .extracting(CanonicalRow::disposition)
        .containsExactly(
            Disposition.QUARANTINED_SUPERSEDED, Disposition.ACCEPTED, Disposition.ACCEPTED);
  }

  @Test
  void validRecordMustBeObservedFirst() {
    CanonicalRecord record = valid(2, ISIN);

    assertThatThrownBy(() -> resolver.resolve(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Record 2 was not observed");
  }

  @Test
  void laterRecordThanTheWinnerWasNotObserved() {
    resolver.observe(valid(2, ISIN));

    assertThatThrownBy(() -> resolver.resolve(valid(3, ISIN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Record 3 was not observed");
  }

  private List<CanonicalRow> resolveAll(CanonicalRecord... records) {
    List.of(records).forEach(resolver::observe);
    return List.of(records).stream().map(resolver::resolve).toList();
  }

  private CanonicalRecord valid(long recordNumber, String isin) {
    return record(recordNumber, Map.of("isin", isin));
  }

  private CanonicalRecord record(long recordNumber, Map<String, String> overrides) {
    return validator.validate(BhavcopyContract.row(recordNumber, overrides));
  }
}
