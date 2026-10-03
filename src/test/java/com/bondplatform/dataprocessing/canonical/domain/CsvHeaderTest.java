package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test cases U-CSV-01..03. */
class CsvHeaderTest {

  // U-CSV-01
  @Test
  void headersMatchAfterTrimmingAndCaseFoldingInAnyOrderWithExtraColumns() {
    List<String> headers = new ArrayList<>(headers());
    Collections.reverse(headers);
    headers.replaceAll(
        header -> header.equals("Number of Trades") ? "  number OF trades " : header);

    CsvHeader header = resolved(headers);

    assertThat(header.columnCount()).isEqualTo(11);
    assertThat(header.positions()).hasSize(10);
    assertThat(header.positions())
        .containsEntry("number_of_trades", headers.indexOf("  number OF trades "));
    assertThat(header.positions()).containsEntry("isin", headers.indexOf("ISIN No."));
    assertThat(header.positions()).doesNotContainValue(headers.indexOf("Extra"));
  }

  // U-CSV-02
  @Test
  void missingSelectedHeaderIsRejectedNamingIt() {
    List<String> headers = new ArrayList<>(headers());
    headers.remove("FACE VALUE");

    assertThat(rejection(headers))
        .isEqualTo(
            new FileRejection(
                ErrorCode.REQUIRED_HEADER_MISSING, "Headers are missing: [FACE VALUE]"));
  }

  // U-CSV-03
  @Test
  void duplicateHeadersAreRejectedEvenWhenNotSelected() {
    List<String> selected = new ArrayList<>(headers());
    selected.add(" isin no. ");
    List<String> unselected = new ArrayList<>(headers());
    unselected.add("extra");

    assertThat(rejection(selected).code()).isEqualTo(ErrorCode.DUPLICATE_HEADER);
    assertThat(rejection(unselected))
        .isEqualTo(
            new FileRejection(
                ErrorCode.DUPLICATE_HEADER, "Headers appear more than once: [extra]"));
  }

  private static List<String> headers() {
    return Arrays.asList(BhavcopyContract.HEADER.split(","));
  }

  private static CsvHeader resolved(List<String> headers) {
    CsvHeader.Resolution resolution =
        CsvHeader.resolve(headers, BhavcopyContract.CONTRACT.fields());
    assertThat(resolution).isInstanceOf(CsvHeader.Resolution.Resolved.class);
    return ((CsvHeader.Resolution.Resolved) resolution).header();
  }

  private static FileRejection rejection(List<String> headers) {
    CsvHeader.Resolution resolution =
        CsvHeader.resolve(headers, BhavcopyContract.CONTRACT.fields());
    assertThat(resolution).isInstanceOf(CsvHeader.Resolution.Rejected.class);
    return ((CsvHeader.Resolution.Rejected) resolution).rejection();
  }
}
