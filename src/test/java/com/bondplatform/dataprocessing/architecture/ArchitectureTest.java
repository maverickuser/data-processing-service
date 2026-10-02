package com.bondplatform.dataprocessing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Applies the structural rules to production code, and proves each rule reports every class of a
 * fixture written to break it (test cases U-ARCH-01 to U-ARCH-03).
 */
class ArchitectureTest {

  private static final String BASE_PACKAGE = "com.bondplatform.dataprocessing";
  private static final String FIXTURES = BASE_PACKAGE + ".architecture.fixture.";

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE_PACKAGE);

  static Stream<Arguments> productionRules() {
    return Stream.of(
        rule("domain is framework free", ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE),
        rule(
            "domain depends on no outer layer", ArchitectureRules.DOMAIN_DEPENDS_ON_NO_OUTER_LAYER),
        rule(
            "only adapters depend on adapters", ArchitectureRules.ONLY_ADAPTERS_DEPEND_ON_ADAPTERS),
        rule(
            "features keep internals private",
            ArchitectureRules.featuresKeepTheirInternalsPrivate(BASE_PACKAGE)),
        rule(
            "features are free of cycles", ArchitectureRules.packagesAreFreeOfCycles(BASE_PACKAGE)),
        rule("lambda types stay at the edge", ArchitectureRules.LAMBDA_TYPES_STAY_AT_THE_EDGE),
        rule("no framework status exceptions", ArchitectureRules.NO_FRAMEWORK_STATUS_EXCEPTIONS),
        rule("no floating-point fields", ArchitectureRules.NO_FLOATING_POINT_FIELDS),
        rule("no floating-point signatures", ArchitectureRules.NO_FLOATING_POINT_SIGNATURES),
        rule("no floating-point conversions", ArchitectureRules.NO_FLOATING_POINT_CONVERSIONS),
        rule("time and ids are injected", ArchitectureRules.TIME_AND_IDS_ARE_INJECTED),
        rule("packages are null-marked", ArchitectureRules.PACKAGES_ARE_NULL_MARKED));
  }

  @ParameterizedTest
  @MethodSource("productionRules")
  void productionCodeSatisfies(ArchRule rule) {
    rule.check(PRODUCTION_CLASSES);
  }

  static Stream<Arguments> violations() {
    return Stream.of(
        violation(
            "framework type in domain",
            ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE,
            "frameworkdomain",
            "SpringDependentValue"),
        violation(
            "domain using application and adapter",
            ArchitectureRules.DOMAIN_DEPENDS_ON_NO_OUTER_LAYER,
            "outerlayer",
            "DomainUsingApplication",
            "DomainUsingAdapter"),
        violation(
            "application using adapter",
            ArchitectureRules.ONLY_ADAPTERS_DEPEND_ON_ADAPTERS,
            "outerlayer",
            "UseCaseUsingAdapter"),
        violation(
            "feature reaching into another feature",
            ArchitectureRules.featuresKeepTheirInternalsPrivate(FIXTURES + "crossfeature"),
            "crossfeature",
            "AlphaAdapterUsingBetaAdapter",
            "AlphaAdapterUsingBetaInternal",
            "AlphaUseCaseUsingBetaInternal",
            "AlphaUseCaseUsingSharedInternal",
            "EntryPointUsingBetaInternal"),
        violation(
            "package cycle",
            ArchitectureRules.packagesAreFreeOfCycles(FIXTURES + "cycle"),
            "cycle",
            "First",
            "Second"),
        violation(
            "lambda type in a use case",
            ArchitectureRules.LAMBDA_TYPES_STAY_AT_THE_EDGE,
            "lambdaedge",
            "UseCaseWithLambdaType"),
        violation(
            "framework status exception",
            ArchitectureRules.NO_FRAMEWORK_STATUS_EXCEPTIONS,
            "statusexception",
            "ThrowsResponseStatusException"),
        violation(
            "floating-point fields",
            ArchitectureRules.NO_FLOATING_POINT_FIELDS,
            "floatingpoint.field",
            "PrimitiveField",
            "BoxedField",
            "ArrayField",
            "GenericField"),
        violation(
            "floating-point signatures",
            ArchitectureRules.NO_FLOATING_POINT_SIGNATURES,
            "floatingpoint.signature",
            "FloatingConstructor",
            "FloatingParameter",
            "FloatingReturn",
            "GenericParameter"),
        violation(
            "floating-point conversions",
            ArchitectureRules.NO_FLOATING_POINT_CONVERSIONS,
            "floatingpoint.conversion",
            "ParsesDouble",
            "ReadsDoubleValue",
            "ReadsFloatValue",
            "BuildsDecimalFromDouble",
            "ConvertsDoubleToDecimal",
            "ReadsNumberAsDouble",
            "RaisesToPower",
            "ReferencesDoubleValue"),
        violation(
            "system clock and random ids",
            ArchitectureRules.TIME_AND_IDS_ARE_INJECTED,
            "systemtime",
            "InstantNow",
            "LocalDateNow",
            "LocalDateNowInZone",
            "LocalDateTimeNow",
            "ZonedDateTimeNow",
            "OffsetDateTimeNow",
            "YearNow",
            "SystemClock",
            "CurrentTimeMillis",
            "NewDate",
            "RandomUuid",
            "InstantNowReference",
            "RandomUuidReference",
            "NewDateReference",
            "CalendarInstance",
            "SystemInstantSource"),
        violation(
            "package without @NullMarked",
            ArchitectureRules.PACKAGES_ARE_NULL_MARKED,
            "unmarked",
            "InUnmarkedPackage"));
  }

  @ParameterizedTest
  @MethodSource("violations")
  void ruleReportsEveryViolatingFixtureClass(
      ArchRule rule, String fixturePackage, List<String> violatingClasses) {
    JavaClasses fixture = new ClassFileImporter().importPackages(FIXTURES + fixturePackage);

    String report = String.join("\n", rule.evaluate(fixture).getFailureReport().getDetails());

    assertThat(violatingClasses)
        .allSatisfy(className -> assertThat(report).contains("." + className));
  }

  @ParameterizedTest
  @MethodSource("violations")
  void ruleAcceptsTheCompliantFixture(ArchRule rule, String fixturePackage, List<String> ignored) {
    JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + "compliant");

    assertThat(rule.evaluate(compliant).hasViolation()).isFalse();
  }

  @Test
  void compliantFeaturesMayUseSharedAdaptersAndBeWiredByEntryPoints() {
    String compliantBase = FIXTURES + "compliant";
    JavaClasses compliant = new ClassFileImporter().importPackages(compliantBase);

    assertThat(
            ArchitectureRules.featuresKeepTheirInternalsPrivate(compliantBase)
                .evaluate(compliant)
                .hasViolation())
        .isFalse();
    assertThat(
            ArchitectureRules.packagesAreFreeOfCycles(compliantBase)
                .evaluate(compliant)
                .hasViolation())
        .isFalse();
  }

  private static Arguments rule(String name, ArchRule rule) {
    return Arguments.of(Named.of(name, rule));
  }

  private static Arguments violation(
      String name, ArchRule rule, String fixturePackage, String... violatingClasses) {
    return Arguments.of(Named.of(name, rule), fixturePackage, List.of(violatingClasses));
  }
}
