package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads every collection a JSON source contract selects from one parsed file (LLD sections 13.4,
 * 13.6, and 13.7).
 *
 * <ul>
 *   <li>A missing or {@code null} collection selects nothing; an empty array has no entries.
 *   <li>A collection that is not an array, or an entry that is not an object, is a {@link
 *       StructureIssue}; that collection or entry is quarantined and the rest continue.
 *   <li>Every field of an entry is read on its own, so an invalid field is rejected alone.
 *   <li>An entry with no valid non-blank selected value is skipped; its errors are kept.
 * </ul>
 */
public final class CollectionExtractor {

  private final List<Collection> collections;
  private final JsonFieldReader reader;

  /** Prepares the collections of a contract that has already passed contract validation. */
  public CollectionExtractor(SourceContract.Json contract, RuleRegistry registry) {
    this.collections =
        contract.collections().stream()
            .map(
                collection ->
                    new Collection(
                        collection,
                        JsonPath.parse(collection.path()),
                        collection.fields().stream()
                            .map(field -> JsonPath.parse("$." + field.path()))
                            .toList()))
            .toList();
    this.reader = new JsonFieldReader(registry);
  }

  /** Returns every entry of every selected collection, in contract and document order. */
  public CollectionExtraction extract(JsonValue root) {
    List<JsonCollectionEntry> entries = new ArrayList<>();
    List<StructureIssue> issues = new ArrayList<>();
    for (Collection collection : collections) {
      switch (JsonPathExtractor.collection(root, collection.path())) {
        case JsonMatch.Absent ignored -> {}
        case JsonMatch.WrongStructure wrong -> issues.add(StructureIssue.of(wrong));
        case JsonMatch.Entries found -> {
          for (JsonMatch.Entry entry : found.entries()) {
            if (entry.value() instanceof JsonValue.JsonObject) {
              entries.add(read(collection, entry));
            } else {
              issues.add(
                  StructureIssue.of(
                      new JsonMatch.WrongStructure(
                          entry.path(), "object", entry.value().toSourceValue().kindName())));
            }
          }
        }
      }
    }
    return new CollectionExtraction(entries, issues);
  }

  private JsonCollectionEntry read(Collection collection, JsonMatch.Entry entry) {
    List<JsonField> fields = collection.contract().fields();
    List<JsonCanonicalField> read = new ArrayList<>(fields.size());
    for (int i = 0; i < fields.size(); i++) {
      JsonField field = fields.get(i);
      // An entry is an object and the contract validator allows one property per field path, so
      // the path always resolves; a contract that breaks this fails here, naming the field.
      if (!(JsonPathExtractor.scalar(entry.value(), collection.fieldPaths().get(i))
          instanceof JsonMatch.Found found)) {
        throw new IllegalStateException(
            "Field "
                + field.name()
                + " of "
                + collection.contract().name()
                + " must be one property");
      }
      read.add(
          reader.read(
              field.name(), entry.path() + "." + field.path(), field.type(), found.value()));
    }
    EntryDisposition disposition =
        read.stream().anyMatch(JsonCanonicalField::isUsable)
            ? EntryDisposition.ACCEPTED
            : EntryDisposition.SKIPPED;
    return new JsonCollectionEntry(collection.contract().name(), entry.path(), read, disposition);
  }

  /**
   * The collections of one file.
   *
   * @param entries every entry of every selected collection, accepted and skipped
   * @param structureIssues one per collection that is not an array or entry that is not an object
   */
  public record CollectionExtraction(
      List<JsonCollectionEntry> entries, List<StructureIssue> structureIssues) {

    /** Copies the lists. */
    public CollectionExtraction {
      entries = List.copyOf(entries);
      structureIssues = List.copyOf(structureIssues);
    }
  }

  /** A contract collection with its parsed paths. */
  private record Collection(JsonCollection contract, JsonPath path, List<JsonPath> fieldPaths) {}
}
