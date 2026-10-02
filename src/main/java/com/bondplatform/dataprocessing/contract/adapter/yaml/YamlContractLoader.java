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
 * <p>YAML is parsed with the safe constructor, so a contract can only describe data: it cannot name
 * Java types or run code. This class checks shape only; whether the rules a contract names exist,
 * and whether a mapping fits its source contract, is checked by the contract validator.
 */
public final class YamlContractLoader {

  private static final String CSV = "csv";
  private static final String JSON = "json";

  /** Reads a stage-1 contract. */
  public SourceContract loadSourceContract(String yaml) {
    YamlMapping root = parse(yaml);
    ContractId id = new ContractId(root.text("id"), root.text("version"));
    DatasetUrn dataset = guarded("dataset", () -> new DatasetUrn(root.text("dataset")));
    String format = root.text("format");
    return switch (format) {
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
      YamlMapping security = root.mapping("security");
      RecordMapping primary =
          new RecordMapping(security.text("target"), security.textMap("fields"));
      return new MappingContract(id, sourceContract, primary, collectionMappings(root));
    }
    RecordMapping primary = new RecordMapping(root.text("target"), root.textMap("fields"));
    return new MappingContract(id, sourceContract, primary, List.of());
  }

  private static SourceContract.Csv csvContract(
      ContractId id, DatasetUrn dataset, YamlMapping root) {
    YamlMapping fields = root.mapping("fields");
    List<CsvField> csvFields =
        fields.keys().stream().map(name -> csvField(name, fields.mapping(name))).toList();
    List<RowRule> rowRules =
        root.mappingList("rowRules").stream().map(YamlContractLoader::rowRule).toList();
    return new SourceContract.Csv(
        id,
        dataset,
        root.mapping("file").wholeNumber("maxBytes"),
        csvFields,
        rowRules,
        root.mapping("duplicates").text("key"));
  }

  private static CsvField csvField(String name, YamlMapping field) {
    return new CsvField(
        name,
        field.text("header"),
        fieldType("fields." + name + ".type", field.text("type")),
        field.flag("requiredValue"),
        field.textList("normalize"),
        field.textList("validate"));
  }

  private static RowRule rowRule(YamlMapping rule) {
    Comparison comparison =
        guarded("rowRules.rule", () -> Comparison.fromContractName(rule.text("rule")));
    return new RowRule(comparison, rule.text("left"), rule.text("right"));
  }

  private static SourceContract.Json jsonContract(
      ContractId id, DatasetUrn dataset, YamlMapping root) {
    YamlMapping collections = root.mapping("collections");
    List<JsonCollection> jsonCollections =
        collections.keys().stream()
            .map(
                name -> {
                  YamlMapping collection = collections.mapping(name);
                  return new JsonCollection(
                      name,
                      collection.text("path"),
                      jsonFields("collections." + name + ".fields", collection.mapping("fields")));
                })
            .toList();
    return new SourceContract.Json(
        id,
        dataset,
        root.mapping("file").wholeNumber("maxCombinedBytes"),
        jsonFields("scalars", root.mapping("scalars")),
        jsonCollections);
  }

  private static List<JsonField> jsonFields(String location, YamlMapping fields) {
    return fields.keys().stream()
        .map(
            name -> {
              YamlMapping field = fields.mapping(name);
              FieldType type = fieldType(location + "." + name + ".type", field.text("type"));
              return new JsonField(name, field.text("path"), type);
            })
        .toList();
  }

  private static List<CollectionMapping> collectionMappings(YamlMapping root) {
    YamlMapping collections = root.mapping("collections");
    return collections.keys().stream()
        .map(
            name -> {
              YamlMapping collection = collections.mapping(name);
              return new CollectionMapping(
                  name,
                  collection.text("target"),
                  collection.textMap("constants"),
                  collection.textMap("fields"));
            })
        .toList();
  }

  private static FieldType fieldType(String location, String name) {
    return guarded(location, () -> FieldType.fromContractName(name));
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
      return YamlMapping.root(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
    } catch (YAMLException e) {
      throw new ContractFormatException("(root)", "is not valid YAML: " + e.getMessage());
    }
  }
}
