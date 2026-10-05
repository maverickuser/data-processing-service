package com.bondplatform.dataprocessing.canonical.domain;

import java.util.List;

/**
 * One entry of a selected JSON collection, read field by field (LLD sections 13.4 and 13.6).
 *
 * @param collection the canonical collection name, such as {@code current_ratings}
 * @param path the entry's path, such as {@code $.currentRatings[0]}
 * @param fields one per field the contract selects from the entry, in contract order
 * @param disposition whether the entry may be appended
 */
public record JsonCollectionEntry(
    String collection, String path, List<JsonCanonicalField> fields, EntryDisposition disposition) {

  /** Copies the fields. */
  public JsonCollectionEntry {
    fields = List.copyOf(fields);
  }
}
