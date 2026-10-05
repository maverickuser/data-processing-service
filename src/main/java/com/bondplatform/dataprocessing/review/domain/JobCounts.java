package com.bondplatform.dataprocessing.review.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The counts a finished job shows (LLD section 20.3). A job that failed before it could count
 * anything shows its dataset's counts, each {@code null}.
 */
public final class JobCounts {

  private static final Map<String, List<String>> NAMES =
      Map.of(
          "urn:bond-platform:dataset:bse-debt-trades",
          List.of("sourceRecords", "acceptedRows", "invalidRows", "supersededRows"),
          "urn:bond-platform:dataset:nsdl-security",
          List.of(
              "filesListed",
              "filesProcessed",
              "filesSkipped",
              "scalarFieldsChanged",
              "collectionEntriesAppended",
              "fieldsRejected"));

  private JobCounts() {}

  /**
   * Returns a finished job's counts: the stored ones, or else its dataset's counts set to {@code
   * null}, or {@code null} for a dataset with no agreed counts.
   */
  public static @Nullable Map<String, @Nullable Long> ofFinished(
      String dataset, @Nullable Map<String, @Nullable Long> stored) {
    if (stored != null) {
      return stored;
    }
    List<String> names = NAMES.get(dataset);
    if (names == null) {
      return null;
    }
    Map<String, @Nullable Long> unknown = new LinkedHashMap<>();
    names.forEach(name -> unknown.put(name, null));
    return unknown;
  }
}
