package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Where each selected field is in a CSV's records (LLD section 5.1).
 *
 * <p>Headers are compared after trimming and locale-independent case-folding, so {@code Number of
 * Trades } matches {@code number of trades}. Columns may come in any order and extra columns are
 * allowed, but no two headers may be the same after that normalization, selected or not.
 *
 * @param columnCount how many cells every record must have
 * @param positions the zero-based column index of each selected field, by canonical field name
 */
public record CsvHeader(int columnCount, Map<String, Integer> positions) {

  /** Copies the positions. */
  public CsvHeader {
    positions = Map.copyOf(positions);
  }

  /** Resolves the selected fields' columns, or returns why the header row is unusable. */
  public static Resolution resolve(List<String> headers, List<CsvField> fields) {
    Map<String, Integer> columns = new HashMap<>();
    TreeSet<String> duplicates = new TreeSet<>();
    for (int index = 0; index < headers.size(); index++) {
      String normalized = normalized(headers.get(index));
      if (columns.putIfAbsent(normalized, index) != null) {
        duplicates.add(headers.get(index).strip());
      }
    }
    if (!duplicates.isEmpty()) {
      return new Resolution.Rejected(
          new FileRejection(
              ErrorCode.DUPLICATE_HEADER, "Headers appear more than once: " + quoted(duplicates)));
    }
    Map<String, Integer> positions = new LinkedHashMap<>();
    TreeSet<String> missing = new TreeSet<>();
    for (CsvField field : fields) {
      Integer column = columns.get(normalized(field.header()));
      if (column == null) {
        missing.add(field.header());
      } else {
        positions.put(field.name(), column);
      }
    }
    if (!missing.isEmpty()) {
      return new Resolution.Rejected(
          new FileRejection(ErrorCode.REQUIRED_HEADER_MISSING, "Headers are missing: " + missing));
    }
    return new Resolution.Resolved(new CsvHeader(headers.size(), positions));
  }

  /** Quotes each name, so that a repeated blank header is visible. */
  private static String quoted(TreeSet<String> names) {
    return names.stream().map(name -> '"' + name + '"').collect(Collectors.joining(", ", "[", "]"));
  }

  /**
   * Trims and lowercases a header without regard to the default locale. This is simple lowercasing,
   * not full Unicode case folding, which is enough for the contracts' ASCII headers.
   */
  private static String normalized(String header) {
    return header.strip().toLowerCase(Locale.ROOT);
  }

  /** The outcome of resolving a header row. */
  public sealed interface Resolution {

    /** Every selected field has its column. */
    record Resolved(CsvHeader header) implements Resolution {}

    /** The header row is unusable. */
    record Rejected(FileRejection rejection) implements Resolution {}
  }
}
