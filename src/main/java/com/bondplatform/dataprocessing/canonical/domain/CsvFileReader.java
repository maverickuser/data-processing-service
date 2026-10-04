package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads a bhavcopy's structure and hands each data record's selected cells to the caller, one at a
 * time (LLD section 5.1).
 *
 * <p>The file is UTF-8 RFC 4180: comma-separated, double-quoted, a doubled quote as escape, CRLF or
 * LF record ends. A leading byte-order mark is ignored, and blank lines, including lines of only
 * whitespace, are skipped. The whole file is rejected if it is not valid UTF-8, has broken quoting,
 * has a record with another number of cells than the header, lacks a selected header, repeats a
 * header, or has no data record.
 *
 * <p>A rejection can come after rows were handed over, because a problem late in the file is only
 * found there. The caller must therefore treat rows as provisional until the read completes.
 */
public final class CsvFileReader {

  private static final char BYTE_ORDER_MARK = '﻿';
  private static final CSVFormat RFC_4180 =
      CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get();

  private CsvFileReader() {}

  /**
   * Reads the file and passes every data record's selected cells to {@code rows}, in file order.
   *
   * @return the number of data records, or why the file is rejected
   */
  public static Outcome read(
      InputStream content, SourceContract.Csv contract, Consumer<CsvRow> rows) {
    try (Reader reader = withoutByteOrderMark(strictUtf8(content));
        CSVParser parser = RFC_4180.parse(reader)) {
      Iterator<CSVRecord> records =
          parser.stream().filter(record -> !isBlankLine(record)).iterator();
      if (!records.hasNext()) {
        return rejected(ErrorCode.EMPTY_FILE, "The file is empty");
      }
      CsvHeader.Resolution resolution =
          CsvHeader.resolve(records.next().toList(), contract.fields());
      if (resolution instanceof CsvHeader.Resolution.Rejected rejected) {
        return new Outcome.Rejected(rejected.rejection());
      }
      CsvHeader header = ((CsvHeader.Resolution.Resolved) resolution).header();
      long count = 0;
      while (records.hasNext()) {
        CSVRecord record = records.next();
        count++;
        // The header is record 1 (LLD section 4.2); blank lines are not records.
        long recordNumber = count + 1;
        if (record.size() != header.columnCount()) {
          return rejected(
              ErrorCode.MALFORMED_CSV,
              "Record "
                  + recordNumber
                  + " has "
                  + record.size()
                  + " cells, but the header has "
                  + header.columnCount());
        }
        rows.accept(new CsvRow(recordNumber, selected(record, header)));
      }
      if (count == 0) {
        return rejected(ErrorCode.EMPTY_FILE, "The file has a header and no data record");
      }
      return new Outcome.Completed(count);
    } catch (CharacterCodingException e) {
      return notUtf8();
    } catch (IOException | UncheckedIOException e) {
      // Commons CSV reports broken quoting, and an undecodable byte met while iterating, as I/O
      // failures; the content itself is in memory, so nothing else can fail here.
      return e.getCause() instanceof CharacterCodingException
          ? notUtf8()
          : rejected(ErrorCode.MALFORMED_CSV, "The file is not valid CSV: broken quoting");
    }
  }

  private static Map<String, CsvCell> selected(CSVRecord record, CsvHeader header) {
    Map<String, CsvCell> cells = new HashMap<>();
    header
        .positions()
        .forEach((field, column) -> cells.put(field, new CsvCell(column + 1, record.get(column))));
    return cells;
  }

  /**
   * Returns whether a record is a line holding only whitespace, which counts as blank (LLD section
   * 5.1). A record with more than one cell has a separator, so it is never blank. A line holding
   * only a quoted empty cell ({@code ""}) is blank too; a single cell could never match a
   * multi-column header anyway.
   */
  private static boolean isBlankLine(CSVRecord record) {
    return record.size() == 1 && record.get(0).isBlank();
  }

  private static Reader strictUtf8(InputStream content) {
    return new InputStreamReader(
        content,
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
  }

  private static Reader withoutByteOrderMark(Reader reader) throws IOException {
    PushbackReader pushback = new PushbackReader(reader, 1);
    int first = pushback.read();
    if (first != -1 && first != BYTE_ORDER_MARK) {
      pushback.unread(first);
    }
    return pushback;
  }

  private static Outcome notUtf8() {
    return rejected(ErrorCode.MALFORMED_CSV, "The file is not valid UTF-8");
  }

  private static Outcome rejected(ErrorCode code, String detail) {
    return new Outcome.Rejected(new FileRejection(code, detail));
  }

  /** The outcome of reading a file. */
  public sealed interface Outcome {

    /** Every record was read and handed over. */
    record Completed(long rowCount) implements Outcome {}

    /** The file is rejected; rows handed over before must be discarded. */
    record Rejected(FileRejection rejection) implements Outcome {}
  }
}
