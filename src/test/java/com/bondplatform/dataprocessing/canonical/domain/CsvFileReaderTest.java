package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test cases U-CSV-04..07, and the reader's half of U-CSV-01..03. */
class CsvFileReaderTest {

  private static final String ROW_1 =
      "1001,INE121A07QY9,100.50,101.00,99.50,100.75,\"1,14,200\",12,\"1,14,200.00\",1000,x";
  private static final String ROW_2 = "1002,INE002A08534,99,99,99,99,10,1,990,100,y";

  private final List<CsvRow> rows = new ArrayList<>();

  @Test
  void readsEverySelectedCellOfEveryRecordInOrder() {
    CsvFileReader.Outcome outcome =
        read(BhavcopyContract.HEADER + "\n" + ROW_1 + "\r\n" + ROW_2 + "\n");

    assertThat(outcome).isEqualTo(new CsvFileReader.Outcome.Completed(2));
    assertThat(rows).extracting(CsvRow::recordNumber).containsExactly(2L, 3L);
    CsvRow first = rows.get(0);
    assertThat(first.cells()).hasSize(10);
    assertThat(first.cell("isin")).contains("INE121A07QY9");
    assertThat(first.cell("security_code")).contains("1001");
    assertThat(first.cell("face_value")).contains("1000");
    assertThat(first.cell("extra")).isEmpty();
  }

  // U-CSV-07
  @Test
  void quotedGroupedNumberIsOneCell() {
    read(BhavcopyContract.HEADER + "\n" + ROW_1 + "\n");

    assertThat(rows.get(0).cell("traded_volume")).contains("1,14,200");
    assertThat(rows.get(0).cell("turnover")).contains("1,14,200.00");
  }

  // U-CSV-06
  @Test
  void byteOrderMarkIsIgnoredAndBlankLinesAreSkippedAndNotCounted() {
    CsvFileReader.Outcome outcome =
        read("﻿" + BhavcopyContract.HEADER + "\n\n" + ROW_1 + "\n\r\n\n" + ROW_2 + "\n\n");

    assertThat(outcome).isEqualTo(new CsvFileReader.Outcome.Completed(2));
    assertThat(rows).extracting(CsvRow::recordNumber).containsExactly(2L, 3L);
    assertThat(rows.get(0).cell("security_code")).contains("1001");
  }

  // U-CSV-06
  @Test
  void invalidUtf8IsMalformedCsv() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(
        (BhavcopyContract.HEADER + "\n" + ROW_1 + "\n").getBytes(StandardCharsets.UTF_8));
    bytes.writeBytes(new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});

    assertThat(
            CsvFileReader.read(
                new ByteArrayInputStream(bytes.toByteArray()),
                BhavcopyContract.CONTRACT,
                rows::add))
        .isEqualTo(rejected(ErrorCode.MALFORMED_CSV, "The file is not valid UTF-8"));
  }

  // U-CSV-04
  @ParameterizedTest
  @ValueSource(strings = {"", "\n\n", "﻿"})
  void emptyFileIsRejected(String content) {
    assertThat(read(content)).isEqualTo(rejected(ErrorCode.EMPTY_FILE, "The file is empty"));
  }

  // U-CSV-04
  @Test
  void headerOnlyFileIsRejected() {
    assertThat(read(BhavcopyContract.HEADER + "\n\n"))
        .isEqualTo(rejected(ErrorCode.EMPTY_FILE, "The file has a header and no data record"));
  }

  // U-CSV-05
  @Test
  void recordWithWrongCellCountLateInTheFileRejectsTheWholeFile() {
    CsvFileReader.Outcome outcome =
        read(BhavcopyContract.HEADER + "\n" + ROW_1 + "\n" + ROW_2 + "\n" + "1003,INE,1,2\n");

    assertThat(outcome)
        .isEqualTo(
            rejected(ErrorCode.MALFORMED_CSV, "Record 4 has 4 cells, but the header has 11"));
  }

  // U-CSV-11
  @Test
  void recordSpanningTwoLinesHasOneNumber() {
    String spanning = "1001,INE121A07QY9,1,1,1,1,1,1,1,1,\"two\nlines\"";

    read(BhavcopyContract.HEADER + "\n" + spanning + "\n" + ROW_2 + "\n");

    assertThat(rows).extracting(CsvRow::recordNumber).containsExactly(2L, 3L);
  }

  // U-CSV-05
  @Test
  void brokenQuotingRejectsTheWholeFile() {
    assertThat(read(BhavcopyContract.HEADER + "\n" + ROW_1 + "\n1002,\"INE002A08534,99\n"))
        .isEqualTo(rejected(ErrorCode.MALFORMED_CSV, "The file is not valid CSV: broken quoting"));
  }

  @Test
  void headerProblemsRejectTheFileBeforeAnyRow() {
    assertThat(read("Security_cd,ISIN No.\n1001,INE121A07QY9\n"))
        .isInstanceOfSatisfying(
            CsvFileReader.Outcome.Rejected.class,
            rejected ->
                assertThat(rejected.rejection().code())
                    .isEqualTo(ErrorCode.REQUIRED_HEADER_MISSING));
    assertThat(rows).isEmpty();
  }

  @Test
  void rejectionMessagesNeverQuoteCellValues() {
    CsvFileReader.Outcome outcome = read(BhavcopyContract.HEADER + "\nSECRET-VALUE,1\n");

    assertThat(((CsvFileReader.Outcome.Rejected) outcome).rejection().detail())
        .doesNotContain("SECRET-VALUE");
  }

  private CsvFileReader.Outcome read(String content) {
    return CsvFileReader.read(
        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
        BhavcopyContract.CONTRACT,
        rows::add);
  }

  private static CsvFileReader.Outcome rejected(ErrorCode code, String detail) {
    return new CsvFileReader.Outcome.Rejected(new FileRejection(code, detail));
  }
}
