package com.bondplatform.dataprocessing.canonical.domain;

/**
 * One selected cell of a CSV data record.
 *
 * @param columnIndex the cell's one-based column in the file's own column order (LLD section 4.2)
 * @param value the decoded cell text before any normalization, with quoting already removed
 */
public record CsvCell(int columnIndex, String value) {}
