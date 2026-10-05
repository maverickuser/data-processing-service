package com.bondplatform.dataprocessing.review.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The counts a finished job shows (LLD section 20.3): exactly its dataset's agreed counts, in their
 * agreed order, each {@code null} that was not stored, so a job that failed before counting shows
 * them all as {@code null}.
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
   * Returns a finished job's counts, or {@code null} for a dataset with no agreed counts.
   *
   * @param stored the stored counts, or {@code null} if none were stored; other names are left out
   */
  public static @Nullable Map<String, @Nullable Long> ofFinished(
      String dataset, @Nullable Map<String, @Nullable Long> stored) {
    List<String> names = NAMES.get(dataset);
    if (names == null) {
      return null;
    }
    Map<String, @Nullable Long> counts = new LinkedHashMap<>();
    names.forEach(name -> counts.put(name, stored == null ? null : stored.get(name)));
    return counts;
  }
}
