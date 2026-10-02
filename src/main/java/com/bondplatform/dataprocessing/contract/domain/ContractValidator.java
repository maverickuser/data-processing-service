package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.contract.domain.MappingContract.CollectionMapping;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.CsvField;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonCollection;
import com.bondplatform.dataprocessing.contract.domain.SourceContract.JsonField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks that a pair of contracts is consistent before the service accepts any work.
 *
 * <p>The loader has already checked each file's shape. This class checks meaning: every rule a
 * contract names exists, types and rules fit together, and the mapping contract refers only to
 * things its source contract declares. It reports every problem, not just the first.
 */
public final class ContractValidator {

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
  private static final Pattern QUALIFIED_TABLE = Pattern.compile("[a-z_]+\\.[a-z_]+");

  private final RuleRegistry rules;

  /** Creates a validator that knows the given rule set. */
  public ContractValidator(RuleRegistry rules) {
    this.rules = rules;
  }

  /**
   * Throws if either contract of the pair is inconsistent.
   *
   * @throws InvalidContractException naming the contract and listing every problem
   */
  public void requireValid(SourceContract source, MappingContract mapping) {
    List<String> sourceProblems = problemsIn(source);
    if (!sourceProblems.isEmpty()) {
      throw new InvalidContractException(source.id().name(), sourceProblems);
    }
    List<String> mappingProblems = problemsIn(mapping, source);
    if (!mappingProblems.isEmpty()) {
      throw new InvalidContractException(mapping.id().name(), mappingProblems);
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
    Set<String> primaryFields =
        switch (source) {
          case SourceContract.Csv csv -> names(csv.fields(), CsvField::name);
          case SourceContract.Json json -> names(json.scalars(), JsonField::name);
        };
    problems.addAll(
        mappingProblems(
            "fields",
            mapping.primary().target(),
            mapping.primary().fields(),
            Map.of(),
            primaryFields));
    Map<String, JsonCollection> declaredCollections =
        source instanceof SourceContract.Json json
            ? json.collections().stream()
                .collect(Collectors.toMap(JsonCollection::name, Function.identity()))
            : Map.of();
    for (CollectionMapping collection : mapping.collections()) {
      JsonCollection declared = declaredCollections.get(collection.name());
      if (declared == null) {
        problems.add(
            "collection '%s' is not declared by the source contract".formatted(collection.name()));
        continue;
      }
      problems.addAll(
          mappingProblems(
              "collections." + collection.name(),
              collection.target(),
              collection.fields(),
              collection.constants(),
              names(declared.fields(), JsonField::name)));
    }
    return problems;
  }

  private List<String> csvProblems(SourceContract.Csv contract) {
    List<String> problems = new ArrayList<>();
    if (contract.maxBytes() <= 0) {
      problems.add("file.maxBytes must be positive");
    }
    problems.addAll(
        duplicates(
            "header",
            contract.fields().stream()
                .map(field -> field.header().strip().toLowerCase(Locale.ROOT))));
    Map<String, CsvField> fields =
        contract.fields().stream().collect(Collectors.toMap(CsvField::name, Function.identity()));
    for (CsvField field : contract.fields()) {
      String at = "field '" + field.name() + "'";
      if (!CSV_TYPES.contains(field.type())) {
        problems.add(
            at + " has type " + field.type().contractName() + ", which CSV does not support");
      }
      unknownRule(() -> rules.normalizerFor(field.normalize()))
          .ifPresent(e -> problems.add(at + ": " + e));
      unknownRule(() -> rules.numberValidatorFor(field.validate()))
          .ifPresent(e -> problems.add(at + ": " + e));
      if (!field.validate().isEmpty() && !NUMERIC_TYPES.contains(field.type())) {
        problems.add(at + " has number validators but is not a number");
      }
    }
    contract.rowRules().stream()
        .flatMap(rule -> Stream.of(rule.left(), rule.right()))
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
    if (!fields.containsKey(contract.duplicateKey())) {
      problems.add("duplicates.key '" + contract.duplicateKey() + "' is not a declared field");
    }
    return problems;
  }

  private static List<String> jsonProblems(SourceContract.Json contract) {
    List<String> problems = new ArrayList<>();
    if (contract.maxCombinedBytes() <= 0) {
      problems.add("file.maxCombinedBytes must be positive");
    }
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

  private static List<String> mappingProblems(
      String at,
      String target,
      Map<String, String> fields,
      Map<String, String> constants,
      Set<String> declaredCanonicalFields) {
    List<String> problems = new ArrayList<>();
    if (!QUALIFIED_TABLE.matcher(target).matches()) {
      problems.add(at + " target '" + target + "' must be schema.table in snake_case");
    }
    fields.keySet().stream()
        .filter(canonical -> !declaredCanonicalFields.contains(canonical))
        .sorted()
        .forEach(
            canonical ->
                problems.add(
                    at + " maps '" + canonical + "', which the source contract does not declare"));
    problems.addAll(
        duplicates(
            at + " internal field",
            Stream.concat(fields.values().stream(), constants.keySet().stream())));
    return problems;
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
