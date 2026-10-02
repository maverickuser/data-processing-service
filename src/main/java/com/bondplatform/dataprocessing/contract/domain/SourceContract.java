package com.bondplatform.dataprocessing.contract.domain;

import java.util.List;

/**
 * A stage-1 contract: how to read one dataset's source files into validated canonical records.
 *
 * <p>The contract is data. It names fields, types, and rules from the fixed {@link RuleRegistry};
 * it cannot supply code.
 */
public sealed interface SourceContract {

  /** Returns this contract's identity. */
  ContractId id();

  /** Returns the dataset whose submissions select this contract. */
  DatasetUrn dataset();

  /**
   * The contract for a CSV dataset.
   *
   * @param maxBytes the largest accepted file size
   * @param fields the selected columns, in contract order
   * @param rowRules comparisons between two fields of the same row
   * @param duplicateKey the canonical field whose repeats are resolved within a file
   */
  record Csv(
      ContractId id,
      DatasetUrn dataset,
      long maxBytes,
      List<CsvField> fields,
      List<RowRule> rowRules,
      String duplicateKey)
      implements SourceContract {

    /** Copies the lists so the contract is immutable. */
    public Csv {
      fields = List.copyOf(fields);
      rowRules = List.copyOf(rowRules);
    }
  }

  /**
   * The contract for a JSON dataset made of several files about one security.
   *
   * @param maxCombinedBytes the largest accepted total size of the listed files
   * @param scalars single-valued fields
   * @param collections repeated entries, each with its own fields
   */
  record Json(
      ContractId id,
      DatasetUrn dataset,
      long maxCombinedBytes,
      List<JsonField> scalars,
      List<JsonCollection> collections)
      implements SourceContract {

    /** Copies the lists so the contract is immutable. */
    public Json {
      scalars = List.copyOf(scalars);
      collections = List.copyOf(collections);
    }
  }

  /**
   * One selected CSV column.
   *
   * @param name the canonical field name
   * @param header the source column header
   * @param requiredValue whether every row must hold a non-blank value
   * @param normalize names of normalizers, applied in order
   * @param validate names of validators applied to the parsed value
   */
  record CsvField(
      String name,
      String header,
      FieldType type,
      boolean requiredValue,
      List<String> normalize,
      List<String> validate) {

    /** Copies the lists so the field is immutable. */
    public CsvField {
      normalize = List.copyOf(normalize);
      validate = List.copyOf(validate);
    }
  }

  /**
   * A comparison that must hold between two fields of a row when both are present and valid.
   *
   * @param left the canonical name of the left operand
   * @param right the canonical name of the right operand
   */
  record RowRule(Comparison comparison, String left, String right) {}

  /** The comparisons a row rule can name. */
  enum Comparison {
    GREATER_THAN_OR_EQUAL("greaterThanOrEqual"),
    LESS_THAN_OR_EQUAL("lessThanOrEqual");

    private final String contractName;

    Comparison(String contractName) {
      this.contractName = contractName;
    }

    /**
     * Returns the comparison a contract names.
     *
     * @throws IllegalArgumentException if no comparison has that name
     */
    public static Comparison fromContractName(String name) {
      for (Comparison comparison : values()) {
        if (comparison.contractName.equals(name)) {
          return comparison;
        }
      }
      throw new IllegalArgumentException("Unknown row rule: " + name);
    }
  }

  /**
   * One selected JSON value.
   *
   * @param name the canonical field name
   * @param path where the value is: a full path for a scalar, a property name inside a collection
   */
  record JsonField(String name, String path, FieldType type) {}

  /**
   * A repeated JSON structure.
   *
   * @param name the canonical collection name
   * @param path the path selecting every entry, ending in {@code [*]}
   * @param fields the fields read from each entry
   */
  record JsonCollection(String name, String path, List<JsonField> fields) {

    /** Copies the list so the collection is immutable. */
    public JsonCollection {
      fields = List.copyOf(fields);
    }
  }
}
