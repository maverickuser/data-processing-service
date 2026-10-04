package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The golden bhavcopy, and test case U-CSV-12. */
class CsvCanonicalizerTest {

  private static final String FIXTURE = "/fixtures/bse/BSE_fgroup01012026";

  private final CsvCanonicalizer canonicalizer =
      new CsvCanonicalizer(BhavcopyContract.CONTRACT, RuleRegistry.standard());
  private final List<CanonicalRow> rows = new ArrayList<>();

  @Test
  void goldenBhavcopyYieldsGoldenCanonicalRecords() {
    CsvCanonicalizer.Outcome outcome = canonicalizer.canonicalize(fixture(), rows::add);

    assertThat(rows.stream().map(CsvCanonicalizerTest::golden).toList())
        .containsExactlyElementsOf(expected());
    // U-CSV-12
    assertThat(outcome)
        .isEqualTo(new CsvCanonicalizer.Outcome.Completed(new RowCounts(8, 3, 4, 1)));
  }

  @Test
  void rejectedFileHandsOverNoRows() {
    String content = BhavcopyContract.HEADER + "\n1,INE1,1,1,1,1,1,1,1,1,x\n1,2\n";

    CsvCanonicalizer.Outcome outcome = canonicalizer.canonicalize(text(content), rows::add);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            CsvCanonicalizer.Outcome.Rejected.class,
            rejected -> assertThat(rejected.rejection().code()).isEqualTo(ErrorCode.MALFORMED_CSV));
    assertThat(rows).isEmpty();
  }

  @Test
  void contentThatChangesBetweenReadsIsRefused() {
    String first = BhavcopyContract.HEADER + "\n1,INE1,1,1,1,1,1,1,1,1,x\n";
    Iterator<String> reads = List.of(first + "2,INE2,1,1,1,1,1,1,1,1,x\n", first).iterator();

    assertThatThrownBy(() -> canonicalizer.canonicalize(() -> stream(reads.next()), rows::add))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("The content changed between reads");
  }

  /** One golden line: record, disposition, winner, ISIN, close price, volume, error codes. */
  private static String golden(CanonicalRow row) {
    CanonicalRecord record = row.record();
    List<String> codes = new ArrayList<>();
    record
        .fields()
        .values()
        .forEach(field -> field.errors().forEach(e -> codes.add(e.code().name())));
    row.rowErrors().forEach(e -> codes.add(e.code().name()));
    return String.join(
        "|",
        String.valueOf(record.recordNumber()),
        row.disposition().name(),
        row.supersededBy().isPresent() ? String.valueOf(row.supersededBy().getAsLong()) : "",
        parsed(record, "isin"),
        parsed(record, "close_price"),
        parsed(record, "traded_volume"),
        String.join(",", codes));
  }

  private static String parsed(CanonicalRecord record, String field) {
    String value = record.field(field).parsedValue();
    return value == null ? "" : value;
  }

  private static List<String> expected() {
    String text = new String(resource(FIXTURE + ".expected.txt"), StandardCharsets.UTF_8);
    return text.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
  }

  private static Supplier<InputStream> fixture() {
    byte[] bytes = resource(FIXTURE + ".csv");
    return () -> new ByteArrayInputStream(bytes);
  }

  private static Supplier<InputStream> text(String content) {
    return () -> stream(content);
  }

  private static InputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] resource(String path) {
    try (InputStream in = CsvCanonicalizerTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
