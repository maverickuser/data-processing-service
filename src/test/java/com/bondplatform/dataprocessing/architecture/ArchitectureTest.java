package com.bondplatform.dataprocessing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Applies the structural rules to production code, and proves each rule rejects a fixture that
 * breaks it (test cases U-ARCH-01 to U-ARCH-03).
 */
class ArchitectureTest {

  private static final String BASE_PACKAGE = "com.bondplatform.dataprocessing";
  private static final String FIXTURE_PACKAGE = BASE_PACKAGE + ".architecture.fixture";

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE_PACKAGE);

  private static final JavaClasses FIXTURE_CLASSES =
      new ClassFileImporter().importPackages(FIXTURE_PACKAGE);

  static Stream<Arguments> productionRules() {
    return rulesFor(BASE_PACKAGE);
  }

  static Stream<Arguments> fixtureRules() {
    return rulesFor(FIXTURE_PACKAGE);
  }

  @ParameterizedTest
  @MethodSource("productionRules")
  void productionCodeSatisfies(ArchRule rule) {
    rule.check(PRODUCTION_CLASSES);
  }

  @ParameterizedTest
  @MethodSource("fixtureRules")
  void ruleRejectsViolatingFixture(ArchRule rule) {
    assertThat(rule.evaluate(FIXTURE_CLASSES).hasViolation()).isTrue();
  }

  private static Stream<Arguments> rulesFor(String basePackage) {
    return Stream.of(
        rule("domain is framework free", ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE),
        rule(
            "only adapters depend on adapters", ArchitectureRules.ONLY_ADAPTERS_DEPEND_ON_ADAPTERS),
        rule("packages are free of cycles", ArchitectureRules.packagesAreFreeOfCycles(basePackage)),
        rule("no floating-point fields", ArchitectureRules.NO_FLOATING_POINT_FIELDS),
        rule("no floating-point methods", ArchitectureRules.NO_FLOATING_POINT_METHODS),
        rule("time and ids are injected", ArchitectureRules.TIME_AND_IDS_ARE_INJECTED));
  }

  private static Arguments rule(String name, ArchRule rule) {
    return Arguments.of(Named.of(name, rule));
  }
}
