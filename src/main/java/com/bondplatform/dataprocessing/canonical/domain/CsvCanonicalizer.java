package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stage 1 of CSV processing: reads, validates, and resolves every record of a bhavcopy (LLD section
 * 3).
 *
 * <p>The content is read twice so that memory stays bounded by the number of distinct ISINs, not
 * the number of records. The first pass checks the file's structure and finds each ISIN's last
 * valid row; the second hands every row, with its final disposition, to the caller in file order. A
 * structurally rejected file therefore hands over no rows at all. The price is that every row is
 * validated twice, which is cheap for a file of at most 10 MiB.
 */
public final class CsvCanonicalizer {

  private final SourceContract.Csv contract;
  private final CsvRowValidator validator;

  /** Prepares stage 1 for a contract that has already passed contract validation. */
  public CsvCanonicalizer(SourceContract.Csv contract, RuleRegistry registry) {
    this.contract = contract;
    this.validator = new CsvRowValidator(contract, registry);
  }

  /**
   * Canonicalizes a file.
   *
   * @param content opens a new stream over identical bytes on every call; a change between the
   *     reads is detected only when it changes the record count or an ISIN's last valid row
   * @param rows receives every row in file order, only once the whole file has been checked
   * @return the counts, or why the file is rejected
   * @throws IllegalStateException if the second read differs from the first
   */
  public Outcome canonicalize(Supplier<InputStream> content, Consumer<CanonicalRow> rows) {
    DuplicateIsinResolver resolver = new DuplicateIsinResolver(contract.duplicateKey());
    CsvFileReader.Outcome first =
        CsvFileReader.read(
            content.get(), contract, row -> resolver.observe(validator.validate(row)));
    if (first instanceof CsvFileReader.Outcome.Rejected rejected) {
      return new Outcome.Rejected(rejected.rejection());
    }
    Tally tally = new Tally();
    CsvFileReader.Outcome second =
        CsvFileReader.read(
            content.get(),
            contract,
            row -> {
              CanonicalRow resolved = resolver.resolve(validator.validate(row));
              tally.counts = tally.counts.plus(resolved.disposition());
              rows.accept(resolved);
            });
    if (!second.equals(first)) {
      throw new IllegalStateException("The content changed between reads: " + second);
    }
    return new Outcome.Completed(tally.counts);
  }

  /** The running counts of the second pass. */
  private static final class Tally {
    private RowCounts counts = RowCounts.NONE;
  }

  /** The outcome of stage 1. */
  public sealed interface Outcome {

    /** Every record was evaluated and handed over. */
    record Completed(RowCounts counts) implements Outcome {}

    /** The file is rejected as a whole; no row was handed over. */
    record Rejected(FileRejection rejection) implements Outcome {}
  }
}
