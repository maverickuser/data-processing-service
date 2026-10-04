package com.bondplatform.dataprocessing.canonical.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The selected cells of one CSV data record, as text exactly as the file has them.
 *
 * @param recordNumber the record's one-based position with the header as record 1, so the first
 *     data record is 2; blank lines are not counted and a record spanning lines has one number
 * @param cells each selected field's cell, by canonical field name
 */
public record CsvRow(long recordNumber, Map<String, CsvCell> cells) {

  /** Copies the cells. */
  public CsvRow {
    cells = Map.copyOf(cells);
  }

  /** Returns a selected field's cell, or empty for a field this row does not carry. */
  public Optional<String> cell(String field) {
    return Optional.ofNullable(cells.get(field)).map(CsvCell::value);
  }
}
