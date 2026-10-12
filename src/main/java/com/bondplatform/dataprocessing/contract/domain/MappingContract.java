package com.bondplatform.dataprocessing.contract.domain;

import java.util.List;
import java.util.Map;

/**
 * A stage-2 contract: how validated canonical records become the internal model.
 *
 * <p>It only renames and places values. It never parses or normalizes.
 *
 * @param sourceContract the name of the stage-1 contract whose canonical records it consumes
 * @param primary the single-record mapping: a daily market summary row, or the security itself
 * @param collections mappings of repeated entries; empty for CSV
 * @param securityFields for CSV, canonical field name to the security field of the row's ISIN it
 *     fills, such as {@code couponRate}; empty for JSON, whose primary mapping is the security
 */
public record MappingContract(
    ContractId id,
    String sourceContract,
    RecordMapping primary,
    List<CollectionMapping> collections,
    Map<String, String> securityFields) {

  /** Copies the list and map so the contract is immutable. */
  public MappingContract {
    collections = List.copyOf(collections);
    securityFields = Map.copyOf(securityFields);
  }

  /** Creates a contract that fills no security fields from CSV rows. */
  public MappingContract(
      ContractId id,
      String sourceContract,
      RecordMapping primary,
      List<CollectionMapping> collections) {
    this(id, sourceContract, primary, collections, Map.of());
  }

  /**
   * Maps canonical fields to internal fields of one target.
   *
   * @param target the schema-qualified table the record is stored in
   * @param fields canonical field name to internal field name
   */
  public record RecordMapping(String target, Map<String, String> fields) {

    /** Copies the map so the mapping is immutable. */
    public RecordMapping {
      fields = Map.copyOf(fields);
    }
  }

  /**
   * Maps the entries of one canonical collection.
   *
   * @param name the canonical collection name
   * @param target the schema-qualified table the entries are stored in
   * @param constants internal fields given a fixed value, such as {@code sourceCategory}
   * @param fields canonical field name to internal field name
   */
  public record CollectionMapping(
      String name, String target, Map<String, String> constants, Map<String, String> fields) {

    /** Copies the maps so the mapping is immutable. */
    public CollectionMapping {
      constants = Map.copyOf(constants);
      fields = Map.copyOf(fields);
    }
  }
}
