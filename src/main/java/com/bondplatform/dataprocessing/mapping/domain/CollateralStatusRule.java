package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.EntryDisposition;
import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonCollectionEntry;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.domain.InternalModel;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.publication.domain.SecurityCollections;
import com.bondplatform.dataprocessing.publication.domain.SecurityScalars;
import com.bondplatform.dataprocessing.publication.domain.SecurityValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies an explicit {@code Unsecured} collateral status (LLD section 15.2).
 *
 * <p>It works in two steps:
 *
 * <ol>
 *   <li>{@link #screen} checks each file before precedence is resolved. A file whose own collateral
 *       status is {@code Unsecured} but which also supplies a usable coverage basis, coverage, or
 *       collateral asset contradicts itself: each such value is a {@code
 *       CONFLICTING_COLLATERAL_DATA} error and is removed, so it can neither win nor be appended.
 *       Missing, {@code null}, placeholder, and empty values are no conflict.
 *   <li>{@link #apply} acts on the request's winning status. When it is {@code Unsecured}, the
 *       coverage basis and coverage are cleared and no collateral asset is appended.
 * </ol>
 *
 * <p>The status must be exactly {@code Unsecured}: text keeps its letter case (LLD section 13.4),
 * and any other spelling clears nothing.
 */
public final class CollateralStatusRule {

  /** The collateral status that makes coverage and assets inapplicable. */
  public static final String UNSECURED = "Unsecured";

  private static final String STATUS = "collateralStatus";
  private static final Set<String> COVERAGE = Set.of("assetCoverageBasis", "assetCoverage");

  private final String statusField;
  private final Set<String> coverageFields;
  private final Set<String> assetCollections;

  /**
   * Creates the rule for a security mapping contract.
   *
   * @throws IllegalArgumentException if the contract does not map the collateral status
   */
  public CollateralStatusRule(MappingContract contract) {
    Map<String, String> byInternal =
        contract.primary().fields().entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    String status = byInternal.get(STATUS);
    if (status == null) {
      throw new IllegalArgumentException("Contract " + contract.id() + " does not map " + STATUS);
    }
    this.statusField = status;
    this.coverageFields =
        COVERAGE.stream()
            .map(byInternal::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    this.assetCollections =
        contract.collections().stream()
            .filter(collection -> collection.target().equals(InternalModel.COLLATERAL_ASSETS))
            .map(CollectionMapping::name)
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * One file's canonical values after screening.
   *
   * @param scalars the file's scalars without conflicting coverage
   * @param entries the file's entries without conflicting collateral assets
   * @param conflicts one per removed value, in file order
   */
  public record Screened(
      List<JsonCanonicalField> scalars,
      List<JsonCollectionEntry> entries,
      List<CollateralConflict> conflicts) {

    /** Copies the lists. */
    public Screened {
      scalars = List.copyOf(scalars);
      entries = List.copyOf(entries);
      conflicts = List.copyOf(conflicts);
    }
  }

  /**
   * A value supplied alongside an {@code Unsecured} status in the same file, and ignored.
   *
   * @param path the JSONPath of the coverage field, or of the collateral asset entry
   * @param rawValue the field's value as read; an object for an asset entry, whose field values
   *     stay in canonical evidence
   * @param issue {@code CONFLICTING_COLLATERAL_DATA}
   * @param actionTaken what the service did with the value
   */
  public record CollateralConflict(
      String path, SourceValue rawValue, ValidationIssue issue, String actionTaken) {}

  /** Removes the coverage and collateral assets a file supplies alongside its own Unsecured. */
  public Screened screen(List<JsonCanonicalField> scalars, List<JsonCollectionEntry> entries) {
    boolean unsecured =
        scalars.stream()
            .anyMatch(
                field ->
                    field.name().equals(statusField)
                        && field.isUsable()
                        && UNSECURED.equals(field.parsedValue()));
    if (!unsecured) {
      return new Screened(scalars, entries, List.of());
    }
    List<CollateralConflict> conflicts = new ArrayList<>();
    List<JsonCanonicalField> keptScalars = new ArrayList<>();
    for (JsonCanonicalField field : scalars) {
      if (coverageFields.contains(field.name()) && field.isUsable()) {
        conflicts.add(
            conflict(
                field.path(),
                field.rawValue(),
                "Asset coverage was supplied for an Unsecured instrument.",
                "Ignored the supplied coverage."));
      } else {
        keptScalars.add(field);
      }
    }
    List<JsonCollectionEntry> keptEntries = new ArrayList<>();
    for (JsonCollectionEntry entry : entries) {
      if (assetCollections.contains(entry.collection())
          && entry.disposition() == EntryDisposition.ACCEPTED) {
        conflicts.add(
            conflict(
                entry.path(),
                new SourceValue.Structured(SourceValue.Structured.Kind.OBJECT),
                "A collateral asset was supplied for an Unsecured instrument.",
                "Did not record the supplied asset."));
      } else {
        keptEntries.add(entry);
      }
    }
    return new Screened(keptScalars, keptEntries, conflicts);
  }

  /**
   * The request's values after the winning status is applied.
   *
   * @param scalars the security fields, with coverage cleared when the status is Unsecured
   * @param collections the entries, without collateral assets when the status is Unsecured
   */
  public record Applied(SecurityScalars scalars, SecurityCollections collections) {}

  /** Clears coverage and drops collateral assets when the request's status is Unsecured. */
  public Applied apply(SecurityScalars scalars, SecurityCollections collections) {
    SecurityScalars.Field status = scalars.fields().get(STATUS);
    if (status == null || !status.value().equals(new SecurityValue.Text(UNSECURED))) {
      return new Applied(scalars, collections);
    }
    return new Applied(scalars.clearing(COVERAGE), collections.withoutCollateralAssets());
  }

  private static CollateralConflict conflict(
      String path, SourceValue rawValue, String message, String actionTaken) {
    return new CollateralConflict(
        path,
        rawValue,
        new ValidationIssue(ErrorCode.CONFLICTING_COLLATERAL_DATA, message),
        actionTaken);
  }
}
