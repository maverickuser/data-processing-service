package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.EntryDisposition;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonCollectionEntry;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.publication.domain.CashFlow;
import com.bondplatform.dataprocessing.publication.domain.CollateralAsset;
import com.bondplatform.dataprocessing.publication.domain.Listing;
import com.bondplatform.dataprocessing.publication.domain.Rating;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityEntry;
import com.bondplatform.dataprocessing.publication.domain.SourceReference;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.JobId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Turns a request's accepted collection entries into cash flows, listings, ratings, and collateral
 * assets, as the mapping contract directs (LLD sections 13.4, 13.6, and 15.4 to 15.6).
 *
 * <ul>
 *   <li>Only usable fields carry a value. A field that is missing, {@code null}, a placeholder, or
 *       invalid is absent, so entries differing only in that way are the same entry.
 *   <li>Entries with equal values are one entry; the first one given keeps its source.
 *   <li>A rating's source category comes from its collection's constant, so the same values in the
 *       current and the earlier list are two entries.
 *   <li>Skipped entries, which have no usable field, are left out.
 * </ul>
 */
public final class CollectionEntryMapper {

  private static final Set<String> TARGETS =
      Set.of(
          InternalModel.CASH_FLOWS,
          InternalModel.LISTINGS,
          InternalModel.RATINGS,
          InternalModel.COLLATERAL_ASSETS);

  private final Map<String, CollectionMapping> collections;

  /**
   * Creates a mapper for a security mapping contract that has passed contract validation.
   *
   * @throws IllegalArgumentException if a collection targets a table that holds no security entries
   */
  public CollectionEntryMapper(MappingContract contract) {
    for (CollectionMapping collection : contract.collections()) {
      if (!TARGETS.contains(collection.target())) {
        throw new IllegalArgumentException(
            "Collection " + collection.name() + " targets " + collection.target());
      }
    }
    this.collections =
        contract.collections().stream()
            .collect(Collectors.toUnmodifiableMap(CollectionMapping::name, Function.identity()));
  }

  /**
   * One file's collection entries.
   *
   * @param objectKey the file's full S3 object key
   * @param entries the file's entries, accepted and skipped
   */
  public record EntrySource(String objectKey, List<JsonCollectionEntry> entries) {

    /** Copies the list. */
    public EntrySource {
      Objects.requireNonNull(objectKey, "objectKey");
      entries = List.copyOf(entries);
    }
  }

  /**
   * Maps the accepted entries of the given files, in the order given.
   *
   * @param isin the security, from the file names
   * @param jobId the request the entries came from
   * @throws IllegalStateException if an entry belongs to a collection the contract does not map
   */
  public SecurityCollections map(Isin isin, JobId jobId, List<EntrySource> sources) {
    Map<Object, SecurityEntry<?>> distinct = new LinkedHashMap<>();
    for (EntrySource source : sources) {
      String fileName = source.objectKey().substring(source.objectKey().lastIndexOf('/') + 1);
      for (JsonCollectionEntry entry : source.entries()) {
        if (entry.disposition() == EntryDisposition.ACCEPTED) {
          Object value = value(entry);
          distinct.putIfAbsent(
              value,
              new SecurityEntry<>(isin, value, new SourceReference(jobId, fileName, entry.path())));
        }
      }
    }
    return new SecurityCollections(
        entries(distinct, CashFlow.class),
        entries(distinct, Listing.class),
        entries(distinct, Rating.class),
        entries(distinct, CollateralAsset.class));
  }

  private Object value(JsonCollectionEntry entry) {
    CollectionMapping collection = collections.get(entry.collection());
    if (collection == null) {
      throw new IllegalStateException("No mapping for collection " + entry.collection());
    }
    Values values = new Values(collection.fields(), entry.fields());
    return switch (collection.target()) {
      case InternalModel.CASH_FLOWS ->
          new CashFlow(
              values.text("eventType"),
              values.date("recordDate"),
              values.date("dueDate"),
              values.decimal("amountPayable"),
              values.date("paymentDate"),
              values.decimal("newFaceValue"));
      case InternalModel.LISTINGS ->
          new Listing(values.text("exchangeName"), values.date("listingDate"));
      case InternalModel.RATINGS ->
          new Rating(
              Rating.SourceCategory.valueOf(
                  Objects.requireNonNull(collection.constants().get("sourceCategory"))),
              values.text("ratingAgencyName"),
              values.text("rating"),
              values.text("outlook"),
              values.text("ratingAction"),
              values.date("ratingDate"),
              values.date("ratingChangeDate"),
              values.date("verificationDate"));
      default ->
          new CollateralAsset(
              values.text("assetType"),
              values.text("collateralDescription"),
              values.text("remarks"));
    };
  }

  private static <T> List<SecurityEntry<T>> entries(
      Map<Object, SecurityEntry<?>> distinct, Class<T> kind) {
    return distinct.values().stream()
        .filter(entry -> kind.isInstance(entry.value()))
        .map(entry -> new SecurityEntry<>(entry.isin(), kind.cast(entry.value()), entry.source()))
        .toList();
  }

  /** The parsed value of each usable field of one entry, by internal field name. */
  private static final class Values {

    private final Map<String, String> parsed = new HashMap<>();

    Values(Map<String, String> mapping, List<JsonCanonicalField> fields) {
      for (JsonCanonicalField field : fields) {
        String internal = mapping.get(field.name());
        if (internal != null && field.isUsable()) {
          parsed.put(internal, Objects.requireNonNull(field.parsedValue()));
        }
      }
    }

    @Nullable String text(String internal) {
      return parsed.get(internal);
    }

    @Nullable LocalDate date(String internal) {
      String value = parsed.get(internal);
      return value == null ? null : LocalDate.parse(value);
    }

    /**
     * Returns the number without trailing fraction zeros, so {@code 89400} and {@code 89400.00}
     * make equal entries; a whole number keeps a scale of zero rather than becoming {@code
     * 8.94E+4}.
     */
    @Nullable BigDecimal decimal(String internal) {
      String value = parsed.get(internal);
      if (value == null) {
        return null;
      }
      BigDecimal stripped = new BigDecimal(value).stripTrailingZeros();
      return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
  }
}
