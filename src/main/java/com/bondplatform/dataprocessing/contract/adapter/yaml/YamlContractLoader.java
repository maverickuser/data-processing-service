package com.bondplatform.dataprocessing.contract.adapter.yaml;

import com.bondplatform.dataprocessing.contract.domain.ContractFormatException;
import com.bondplatform.dataprocessing.contract.domain.ContractId;
import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.MappingContract;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.RecordMapping;
import com.bondplatform.dataprocessing.contract.domain.SourceContract;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.Comparison;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.RowRule;
import java.util.List;
import java.util.function.Supplier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Turns the text of a contract file into a typed contract.
 *
 * <p>The loader is strict so that a contract means exactly what it says:
 *
 * <ul>
 *   <li>YAML is parsed with the safe constructor, so a contract can only describe data; it cannot
 *       name Java types or run code.
 *   <li>A key that appears twice in one mapping is an error, never a silent override.
 *   <li>Every key must be one the loader reads. A misspelled key such as {@code normalise}, or a
 *       setting the service does not implement, fails with its location instead of being ignored.
 * </ul>
 *
 * <p>This class checks shape only. Whether the rules a contract names exist, and whether a mapping
 * fits its source contract, is checked by the contract validator.
 */
public final class YamlContractLoader {

  private static final String CSV = "csv";
  private static final String JSON = "json";

  /** Reads a stage-1 contract. */
  public SourceContract loadSourceContract(String yaml) {
    YamlMapping root = parse(yaml);
    ContractId id = new ContractId(root.text("id"), root.text("version"));
    DatasetUrn dataset = guarded("dataset", () -> new DatasetUrn(root.text("dataset")));
    return switch (root.text("format")) {
      case CSV -> csvContract(id, dataset, root);
      case JSON -> jsonContract(id, dataset, root);
      default -> throw new ContractFormatException("format", "expected csv or json");
    };
  }

  /** Reads a stage-2 contract. */
  public MappingContract loadMappingContract(String yaml) {
    YamlMapping root = parse(yaml);
    ContractId id = new ContractId(root.text("id"), root.text("version"));
    String sourceContract = root.text("sourceContract");
    if (root.has("security")) {
      root.allowingOnly("id", "version", "sourceContract", "security", "collections");
      YamlMapping security = root.mapping("security").allowingOnly("target", "fields");
      RecordMapping primary =
          new RecordMapping(security.text("target"), security.textMap("fields"));
      return new MappingContract(id, sourceContract, primary, collectionMappings(root));
    }
    root.allowingOnly("id", "version", "sourceContract", "target", "fields");
    RecordMapping primary = new RecordMapping(root.text("target"), root.textMap("fields"));
    return new MappingContract(id, sourceContract, primary, List.of());
  }

  private static SourceContract.Csv csvContract(
      ContractId id, DatasetUrn dataset, YamlMapping root) {
    root.allowingOnly(
        "id", "version", "dataset", "format", "file", "fields", "rowRules", "duplicates");
    YamlMapping fields = root.mapping("fields");
    List<CsvField> csvFields =
        fields.keys().stream().map(name -> csvField(name, fields.mapping(name))).toList();
    List<RowRule> rowRules =
        root.mappingList("rowRules").stream().map(YamlContractLoader::rowRule).toList();
    return new SourceContract.Csv(
        id,
        dataset,
        root.mapping("file").allowingOnly("maxBytes").wholeNumber("maxBytes"),
        csvFields,
        rowRules,
        root.mapping("duplicates").allowingOnly("key").text("key"));
  }

  private static CsvField csvField(String name, YamlMapping field) {
    field.allowingOnly("header", "type", "requiredValue", "normalize", "validate");
    return new CsvField(
        name,
        field.text("header"),
        fieldType(field),
        field.flag("requiredValue"),
        field.textList("normalize"),
        field.textList("validate"));
  }

  private static RowRule rowRule(YamlMapping rule) {
    rule.allowingOnly("rule", "left", "right");
    Comparison comparison =
        guarded(rule.location() + ".rule", () -> Comparison.fromContractName(rule.text("rule")));
    return new RowRule(comparison, rule.text("left"), rule.text("right"));
  }

  private static SourceContract.Json jsonContract(
      ContractId id, DatasetUrn dataset, YamlMapping root) {
    root.allowingOnly("id", "version", "dataset", "format", "file", "scalars", "collections");
    YamlMapping collections = root.mapping("collections");
    List<JsonCollection> jsonCollections =
        collections.keys().stream()
            .map(
                name -> {
                  YamlMapping collection = collections.mapping(name).allowingOnly("path", "fields");
                  return new JsonCollection(
                      name, collection.text("path"), jsonFields(collection.mapping("fields")));
                })
            .toList();
    return new SourceContract.Json(
        id,
        dataset,
        root.mapping("file").allowingOnly("maxCombinedBytes").wholeNumber("maxCombinedBytes"),
        jsonFields(root.mapping("scalars")),
        jsonCollections);
  }

  private static List<JsonField> jsonFields(YamlMapping fields) {
    return fields.keys().stream()
        .map(
            name -> {
              YamlMapping field = fields.mapping(name).allowingOnly("path", "type");
              return new JsonField(name, field.text("path"), fieldType(field));
            })
        .toList();
  }

  private static List<CollectionMapping> collectionMappings(YamlMapping root) {
    YamlMapping collections = root.mapping("collections");
    return collections.keys().stream()
        .map(
            name -> {
              YamlMapping collection =
                  collections.mapping(name).allowingOnly("target", "constants", "fields");
              return new CollectionMapping(
                  name,
                  collection.text("target"),
                  collection.textMap("constants"),
                  collection.textMap("fields"));
            })
        .toList();
  }

  private static FieldType fieldType(YamlMapping field) {
    return guarded(
        field.location() + ".type", () -> FieldType.fromContractName(field.text("type")));
  }

  /** Runs a conversion, reporting an illegal value with its location in the contract. */
  private static <T> T guarded(String location, Supplier<T> conversion) {
    try {
      return conversion.get();
    } catch (ContractFormatException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new ContractFormatException(location, String.valueOf(e.getMessage()));
    }
  }

  private static YamlMapping parse(String yaml) {
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      return YamlMapping.root(new Yaml(new SafeConstructor(options)).load(yaml));
    } catch (YAMLException e) {
      throw new ContractFormatException("(root)", "is not valid YAML: " + e.getMessage());
    }
  }
}
