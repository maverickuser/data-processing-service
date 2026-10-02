package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.contract.domain.InternalModel.Target;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.contract.domain.MappingContract.RecordMapping;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.RowRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks that a pair of contracts is consistent before the service accepts any work.
 *
 * <p>The loader has already checked each file's shape. This class checks meaning: every rule a
 * contract names exists and fits the field's type, names and paths are unique, and the mapping
 * contract maps exactly what its source contract declares onto fields the internal model has. It
 * reports every problem it finds in both contracts, not just the first.
 */
public final class ContractValidator {

  private static final String GROUPED_NUMBER = "normalizeGroupedNumber";
  private static final Set<String> NUMBER_NORMALIZERS =
      Set.of(GROUPED_NUMBER, "stripTrailingPercent");
  private static final Set<FieldType> CSV_TYPES =
      Set.of(FieldType.TEXT, FieldType.DECIMAL, FieldType.INTEGER);
  private static final Set<FieldType> JSON_TYPES =
      Set.of(FieldType.TEXT, FieldType.DATE, FieldType.DECIMAL, FieldType.PERCENT);
  private static final Set<FieldType> NUMERIC_TYPES =
      Set.of(FieldType.DECIMAL, FieldType.INTEGER, FieldType.PERCENT);
  private static final Pattern SCALAR_PATH = Pattern.compile("\\$(\\.[A-Za-z_][A-Za-z0-9_]*)+");
  private static final Pattern COLLECTION_PATH =
      Pattern.compile("\\$(\\.[A-Za-z_][A-Za-z0-9_]*)+\\[\\*]");
  private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final RuleRegistry rules;

  /** Creates a validator that knows the given rule set. */
  public ContractValidator(RuleRegistry rules) {
    this.rules = rules;
  }

  /**
   * Throws if the pair is inconsistent.
   *
   * @throws InvalidContractException naming both contracts and listing every problem in either
   */
  public void requireValid(SourceContract source, MappingContract mapping) {
    List<String> problems = new ArrayList<>();
    problemsIn(source).forEach(problem -> problems.add(source.id().name() + ": " + problem));
    problemsIn(mapping, source)
        .forEach(problem -> problems.add(mapping.id().name() + ": " + problem));
    if (!problems.isEmpty()) {
      throw new InvalidContractException(
          source.id().name() + " with " + mapping.id().name(), problems);
    }
  }

  /** Returns every problem in a source contract; empty when it is consistent. */
  public List<String> problemsIn(SourceContract source) {
    return switch (source) {
      case SourceContract.Csv csv -> csvProblems(csv);
      case SourceContract.Json json -> jsonProblems(json);
    };
  }

  /** Returns every problem in a mapping contract read against its source contract. */
  public List<String> problemsIn(MappingContract mapping, SourceContract source) {
    List<String> problems = new ArrayList<>();
    if (!mapping.sourceContract().equals(source.id().name())) {
      problems.add(
          "sourceContract is '%s' but it is paired with '%s'"
              .formatted(mapping.sourceContract(), source.id().name()));
    }
    switch (source) {
      case SourceContract.Csv csv -> problems.addAll(csvMappingProblems(mapping, csv));
      case SourceContract.Json json -> problems.addAll(jsonMappingProblems(mapping, json));
    }
    return problems;
  }

  private List<String> csvProblems(SourceContract.Csv contract) {
    List<String> problems = new ArrayList<>();
    if (contract.maxBytes() <= 0) {
      problems.add("file.maxBytes must be positive");
    }
    problems.addAll(duplicates("field", contract.fields().stream().map(CsvField::name)));
    problems.addAll(
        duplicates(
            "header",
            contract.fields().stream()
                .map(field -> field.header().strip().toLowerCase(Locale.ROOT))));
    Map<String, CsvField> fields = byName(contract.fields(), CsvField::name);
    contract.fields().forEach(field -> problems.addAll(csvFieldProblems(field)));
    contract.rowRules().forEach(rule -> problems.addAll(rowRuleProblems(rule, fields)));
    CsvField key = fields.get(contract.duplicateKey());
    if (key == null) {
      problems.add("duplicates.key '" + contract.duplicateKey() + "' is not a declared field");
    } else if (key.type() != FieldType.TEXT || !key.requiredValue()) {
      problems.add(
          "duplicates.key '" + key.name() + "' must be a text field with requiredValue true");
    }
    return problems;
  }

  private List<String> csvFieldProblems(CsvField field) {
    List<String> problems = new ArrayList<>();
    String at = "field '" + field.name() + "'";
    if (!CSV_TYPES.contains(field.type())) {
      problems.add(
          at + " has type " + field.type().contractName() + ", which CSV does not support");
    }
    unknownRule(() -> rules.normalizerFor(field.normalize()))
        .ifPresent(e -> problems.add(at + ": " + e));
    unknownRule(() -> rules.numberValidatorFor(field.validate()))
        .ifPresent(e -> problems.add(at + ": " + e));
    boolean numeric = NUMERIC_TYPES.contains(field.type());
    if (numeric && !field.normalize().contains(GROUPED_NUMBER)) {
      problems.add(at + " is a number and must be normalized with " + GROUPED_NUMBER);
    }
    if (!numeric) {
      field.normalize().stream()
          .filter(NUMBER_NORMALIZERS::contains)
          .forEach(rule -> problems.add(at + " is not a number but is normalized with " + rule));
      if (!field.validate().isEmpty()) {
        problems.add(at + " has number validators but is not a number");
      }
    }
    return problems;
  }

  private static List<String> rowRuleProblems(RowRule rule, Map<String, CsvField> fields) {
    List<String> problems = new ArrayList<>();
    if (rule.left().equals(rule.right())) {
      problems.add("row rule compares '" + rule.left() + "' with itself");
    }
    Stream.of(rule.left(), rule.right())
        .distinct()
        .forEach(
            operand -> {
              CsvField field = fields.get(operand);
              if (field == null) {
                problems.add("row rule operand '" + operand + "' is not a declared field");
              } else if (!NUMERIC_TYPES.contains(field.type())) {
                problems.add("row rule operand '" + operand + "' is not a number");
              }
            });
    return problems;
  }

  private static List<String> jsonProblems(SourceContract.Json contract) {
    List<String> problems = new ArrayList<>();
    if (contract.maxCombinedBytes() <= 0) {
      problems.add("file.maxCombinedBytes must be positive");
    }
    problems.addAll(
        duplicates(
            "scalar or collection name",
            Stream.concat(
                contract.scalars().stream().map(JsonField::name),
                contract.collections().stream().map(JsonCollection::name))));
    problems.addAll(duplicates("scalar path", contract.scalars().stream().map(JsonField::path)));
    problems.addAll(
        duplicates("collection path", contract.collections().stream().map(JsonCollection::path)));
    for (JsonField scalar : contract.scalars()) {
      problems.addAll(jsonFieldProblems("scalar '" + scalar.name() + "'", scalar, SCALAR_PATH));
    }
    for (JsonCollection collection : contract.collections()) {
      String at = "collection '" + collection.name() + "'";
      if (!COLLECTION_PATH.matcher(collection.path()).matches()) {
        problems.add(at + " path must look like $.a.b[*]");
      }
      if (collection.fields().isEmpty()) {
        problems.add(at + " declares no fields");
      }
      problems.addAll(duplicates(at + " field", collection.fields().stream().map(JsonField::name)));
      problems.addAll(
          duplicates(at + " field path", collection.fields().stream().map(JsonField::path)));
      for (JsonField field : collection.fields()) {
        problems.addAll(
            jsonFieldProblems(at + " field '" + field.name() + "'", field, PROPERTY_NAME));
      }
    }
    return problems;
  }

  private static List<String> jsonFieldProblems(String at, JsonField field, Pattern pathShape) {
    List<String> problems = new ArrayList<>();
    if (!JSON_TYPES.contains(field.type())) {
      problems.add(
          at + " has type " + field.type().contractName() + ", which JSON does not support");
    }
    if (!pathShape.matcher(field.path()).matches()) {
      problems.add(at + " has an unsupported path '" + field.path() + "'");
    }
    return problems;
  }

  private static List<String> csvMappingProblems(
      MappingContract mapping, SourceContract.Csv source) {
    List<String> problems = new ArrayList<>();
    problems.addAll(
        recordProblems(
            mapping.primary(),
            InternalModel.DAILY_MARKET_SUMMARIES,
            names(source.fields(), CsvField::name)));
    String keyTarget = mapping.primary().fields().get(source.duplicateKey());
    if (keyTarget != null && !keyTarget.equals("isin")) {
      problems.add("fields must map '" + source.duplicateKey() + "' to 'isin'");
    }
    mapping.collections().stream()
        .map(
            collection ->
                "collection '" + collection.name() + "' is not declared by the source contract")
        .forEach(problems::add);
    return problems;
  }

  private static List<String> jsonMappingProblems(
      MappingContract mapping, SourceContract.Json source) {
    List<String> problems = new ArrayList<>();
    problems.addAll(
        recordProblems(
            mapping.primary(), InternalModel.SECURITIES, names(source.scalars(), JsonField::name)));
    Map<String, JsonCollection> declared = byName(source.collections(), JsonCollection::name);
    problems.addAll(
        duplicates(
            "collection mapping", mapping.collections().stream().map(CollectionMapping::name)));
    for (CollectionMapping collection : mapping.collections()) {
      JsonCollection declaredCollection = declared.get(collection.name());
      if (declaredCollection == null) {
        problems.add(
            "collection '" + collection.name() + "' is not declared by the source contract");
      } else {
        problems.addAll(
            collectionProblems(collection, names(declaredCollection.fields(), JsonField::name)));
      }
    }
    Set<String> mapped = names(mapping.collections(), CollectionMapping::name);
    declared.keySet().stream()
        .filter(name -> !mapped.contains(name))
        .sorted()
        .forEach(
            name ->
                problems.add(
                    "collection '" + name + "' is declared by the source contract but not mapped"));
    return problems;
  }

  private static List<String> recordProblems(
      RecordMapping mapping, String requiredTarget, Set<String> canonicalFields) {
    List<String> problems = new ArrayList<>();
    if (!mapping.target().equals(requiredTarget)) {
      problems.add(
          "fields target must be " + requiredTarget + " but is '" + mapping.target() + "'");
      return problems;
    }
    Target target = InternalModel.target(requiredTarget).orElseThrow();
    problems.addAll(
        fieldMappingProblems("fields", mapping.fields(), Map.of(), canonicalFields, target));
    return problems;
  }

  private static List<String> collectionProblems(
      CollectionMapping collection, Set<String> canonicalFields) {
    String at = "collections." + collection.name();
    Optional<Target> target = InternalModel.target(collection.target()).filter(Target::appendOnly);
    if (target.isEmpty()) {
      return List.of(at + " target '" + collection.target() + "' is not a collection table");
    }
    return fieldMappingProblems(
        at, collection.fields(), collection.constants(), canonicalFields, target.get());
  }

  private static List<String> fieldMappingProblems(
      String at,
      Map<String, String> fields,
      Map<String, String> constants,
      Set<String> canonicalFields,
      Target target) {
    List<String> problems = new ArrayList<>();
    new TreeSet<>(fields.keySet())
        .stream()
            .filter(canonical -> !canonicalFields.contains(canonical))
            .forEach(
                canonical ->
                    problems.add(
                        at
                            + " maps '"
                            + canonical
                            + "', which the source contract does not declare"));
    new TreeSet<>(canonicalFields)
        .stream()
            .filter(canonical -> !fields.containsKey(canonical))
            .forEach(canonical -> problems.add(at + " does not map '" + canonical + "'"));
    new TreeSet<>(fields.values())
        .stream()
            .filter(internal -> !target.fields().contains(internal))
            .forEach(
                internal ->
                    problems.add(at + " maps to unknown internal field '" + internal + "'"));
    problems.addAll(
        duplicates(
            at + " internal field",
            Stream.concat(fields.values().stream(), constants.keySet().stream())));
    for (Map.Entry<String, Set<String>> required : new TreeMap<>(target.constants()).entrySet()) {
      String value = constants.get(required.getKey());
      if (value == null) {
        problems.add(at + " must set the constant '" + required.getKey() + "'");
      } else if (!required.getValue().contains(value)) {
        problems.add(
            "%s constant '%s' has the unsupported value '%s'"
                .formatted(at, required.getKey(), value));
      }
    }
    new TreeSet<>(constants.keySet())
        .stream()
            .filter(name -> !target.constants().containsKey(name))
            .forEach(name -> problems.add(at + " sets the unknown constant '" + name + "'"));
    return problems;
  }

  private static <T> Map<String, T> byName(Collection<T> items, Function<T, String> name) {
    return items.stream()
        .collect(Collectors.toMap(name, Function.identity(), (first, second) -> first));
  }

  private static <T> Set<String> names(Collection<T> items, Function<T, String> name) {
    return items.stream().map(name).collect(Collectors.toSet());
  }

  private static List<String> duplicates(String what, Stream<String> values) {
    Set<String> seen = new HashSet<>();
    return values
        .filter(value -> !seen.add(value))
        .distinct()
        .sorted()
        .map(value -> "duplicate " + what + " '" + value + "'")
        .toList();
  }

  private static Optional<String> unknownRule(Runnable lookup) {
    try {
      lookup.run();
      return Optional.empty();
    } catch (UnknownRuleException e) {
      return Optional.of(String.valueOf(e.getMessage()));
    }
  }
}
