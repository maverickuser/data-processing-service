package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import java.util.List;

/** What a contract path finds in a parsed JSON document. */
public sealed interface JsonMatch {

  /** What a scalar path, {@code $.a.b}, finds. */
  sealed interface Scalar extends JsonMatch {}

  /** What a collection path, {@code $.a.b[*]}, finds. */
  sealed interface Collection extends JsonMatch {}

  /**
   * The value at a scalar path, which may be {@link SourceValue.Missing} or {@link
   * SourceValue.Null}.
   */
  record Found(SourceValue value) implements Scalar {}

  /** The collection is missing or {@code null}; nothing is selected and nothing is wrong. */
  record Absent() implements Collection {}

  /** The entries of the collection, in document order; an empty array gives none. */
  record Entries(List<Entry> entries) implements Collection {

    /** Copies the entries. */
    public Entries {
      entries = List.copyOf(entries);
    }
  }

  /**
   * One entry of a collection.
   *
   * @param path the entry's own path, such as {@code $.listingDetails[0]}
   * @param value the entry as parsed
   */
  record Entry(String path, JsonValue value) {}

  /**
   * The document has the wrong kind of value where the path needs an object or an array: a
   * structural error that quarantines the section (LLD section 13.7).
   *
   * @param path the path of the value that has the wrong kind, such as {@code $.currentRatings}
   * @param expected {@code object} or {@code array}
   * @param actual the kind found, as named by {@link SourceValue#kindName()}
   */
  record WrongStructure(String path, String expected, String actual)
      implements Scalar, Collection {}
}
