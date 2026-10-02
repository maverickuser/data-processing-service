package com.bondplatform.dataprocessing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;

/**
 * The project's structural rules, from docs/engineering/java-standards.md.
 *
 * <p>Each rule is exposed so that one test applies it to production code and another proves it
 * rejects a fixture that breaks it. The rules inspect class files, so they see fields, signatures,
 * and calls, but not the types of local variables.
 */
final class ArchitectureRules {

  private static final String DOMAIN = "..domain..";
  private static final String APPLICATION = "..application..";
  private static final String ADAPTER = "..adapter..";
  private static final String LAMBDA = "..lambda..";
  private static final String SUPPLIER = "..shared.supplier..";
  private static final Set<String> FLOATING_POINT_TYPES =
      Set.of("float", "double", "java.lang.Float", "java.lang.Double");

  /** U-ARCH-01: domain code is plain Java, free of framework, AWS, JSON, and JDBC types. */
  static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "software.amazon..",
              "com.amazonaws..",
              "tools.jackson..",
              "com.fasterxml..",
              "java.sql..",
              "javax.sql..")
          .allowEmptyShould(true);

  /** U-ARCH-02: domain depends on neither application nor adapter code. */
  static final ArchRule DOMAIN_DEPENDS_ON_NO_OUTER_LAYER =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(APPLICATION, ADAPTER)
          .allowEmptyShould(true);

  /** U-ARCH-02: only adapters and Lambda entry points depend on adapters. */
  static final ArchRule ONLY_ADAPTERS_DEPEND_ON_ADAPTERS =
      noClasses()
          .that()
          .resideOutsideOfPackages(ADAPTER, LAMBDA)
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ADAPTER)
          .allowEmptyShould(true);

  /** U-ARCH-03: no field holds a floating-point value, directly, in an array, or in a generic. */
  static final ArchRule NO_FLOATING_POINT_FIELDS =
      fields().should(notHoldFloatingPoint()).allowEmptyShould(true);

  /** U-ARCH-03: no method or constructor takes or returns floating point. */
  static final ArchRule NO_FLOATING_POINT_SIGNATURES =
      codeUnits().should(notTakeOrReturnFloatingPoint()).allowEmptyShould(true);

  /** U-ARCH-03: no code converts to or from floating point. */
  static final ArchRule NO_FLOATING_POINT_CONVERSIONS =
      noClasses()
          .should()
          .dependOnClassesThat()
          .belongToAnyOf(Double.class, Float.class)
          .orShould()
          .callMethod(BigDecimal.class, "doubleValue")
          .orShould()
          .callMethod(BigDecimal.class, "floatValue")
          .orShould()
          .callMethod(BigDecimal.class, "valueOf", double.class)
          .orShould()
          .callConstructor(BigDecimal.class, double.class)
          .allowEmptyShould(true);

  /**
   * U-ARCH-03: the current time and new identifiers come from the injected {@link Clock} and {@code
   * IdSupplier}; only the supplier package may read the system clock or generate random UUIDs.
   */
  static final ArchRule TIME_AND_IDS_ARE_INJECTED =
      classes()
          .that()
          .resideOutsideOfPackage(SUPPLIER)
          .should(notReadTheSystemClockOrGenerateRandomIds())
          .allowEmptyShould(true);

  /** Every package declares {@code @NullMarked}; NullAway checks only null-marked code. */
  static final ArchRule PACKAGES_ARE_NULL_MARKED =
      classes().should(resideInNullMarkedPackage()).allowEmptyShould(true);

  private ArchitectureRules() {}

  /**
   * U-ARCH-02: the packages directly under {@code basePackage} do not depend on each other in a
   * cycle. For production code these are the feature packages.
   */
  static ArchRule packagesAreFreeOfCycles(String basePackage) {
    return slices()
        .matching(basePackage + ".(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true);
  }

  /**
   * U-ARCH-02: a feature (a package directly under {@code basePackage}) never depends on another
   * feature's adapters or on its {@code application.internal} package.
   */
  static ArchRule featuresKeepTheirInternalsPrivate(String basePackage) {
    return classes().should(notReachIntoAnotherFeature(basePackage)).allowEmptyShould(true);
  }

  private static ArchCondition<JavaClass> notReachIntoAnotherFeature(String basePackage) {
    return new ArchCondition<>("not depend on another feature's adapters or internals") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String originFeature = featureOf(origin, basePackage);
        origin.getDirectDependenciesFromSelf().stream()
            .map(dependency -> dependency.getTargetClass())
            .filter(target -> isPrivateToItsFeature(target.getPackageName()))
            .filter(target -> !featureOf(target, basePackage).isEmpty())
            .filter(target -> !featureOf(target, basePackage).equals(originFeature))
            .forEach(
                target ->
                    events.add(
                        SimpleConditionEvent.violated(
                            origin,
                            origin.getName()
                                + " reaches into another feature: "
                                + target.getName())));
      }
    };
  }

  private static boolean isPrivateToItsFeature(String packageName) {
    String dotted = "." + packageName + ".";
    return dotted.contains(".adapter.") || dotted.contains(".application.internal.");
  }

  /** Returns the package segment directly under the base package, or empty if outside it. */
  private static String featureOf(JavaClass type, String basePackage) {
    String packageName = type.getPackageName();
    if (!packageName.startsWith(basePackage + ".")) {
      return "";
    }
    String remainder = packageName.substring(basePackage.length() + 1);
    int end = remainder.indexOf('.');
    return end < 0 ? remainder : remainder.substring(0, end);
  }

  private static ArchCondition<JavaField> notHoldFloatingPoint() {
    return new ArchCondition<>("not hold floating point") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        if (involvesFloatingPoint(field.getType())) {
          events.add(
              SimpleConditionEvent.violated(field, field.getFullName() + " holds floating point"));
        }
      }
    };
  }

  private static ArchCondition<JavaCodeUnit> notTakeOrReturnFloatingPoint() {
    return new ArchCondition<>("not take or return floating point") {
      @Override
      public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
        boolean floatingPoint =
            Stream.concat(
                    Stream.of(codeUnit.getReturnType()), codeUnit.getParameterTypes().stream())
                .anyMatch(ArchitectureRules::involvesFloatingPoint);
        if (floatingPoint) {
          events.add(
              SimpleConditionEvent.violated(
                  codeUnit, codeUnit.getFullName() + " takes or returns floating point"));
        }
      }
    };
  }

  private static boolean involvesFloatingPoint(JavaType type) {
    return type.getAllInvolvedRawTypes().stream()
        .map(rawType -> rawType.getBaseComponentType().getName())
        .anyMatch(FLOATING_POINT_TYPES::contains);
  }

  private static ArchCondition<JavaClass> notReadTheSystemClockOrGenerateRandomIds() {
    return new ArchCondition<>("not read the system clock or generate random identifiers") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        Stream.concat(
                origin.getMethodCallsFromSelf().stream(),
                origin.getConstructorCallsFromSelf().stream())
            .filter(ArchitectureRules::readsSystemClockOrRandomId)
            .forEach(
                call ->
                    events.add(
                        SimpleConditionEvent.violated(
                            origin,
                            origin.getName() + " calls " + call.getTarget().getFullName())));
      }
    };
  }

  private static boolean readsSystemClockOrRandomId(JavaAccess<?> call) {
    String owner = call.getTargetOwner().getName();
    String name = call.getTarget().getName();
    String signature = call.getTarget().getFullName();
    if (owner.startsWith("java.time.") && name.equals("now")) {
      return !signature.contains(Clock.class.getName());
    }
    if (owner.equals(Clock.class.getName())) {
      return name.startsWith("system") || name.equals("tick") || name.startsWith("tick");
    }
    if (owner.equals(System.class.getName())) {
      return name.equals("currentTimeMillis");
    }
    if (owner.equals(Date.class.getName())) {
      return signature.endsWith("<init>()");
    }
    return owner.equals(UUID.class.getName()) && name.equals("randomUUID");
  }

  private static ArchCondition<JavaClass> resideInNullMarkedPackage() {
    return new ArchCondition<>("reside in a package annotated with @NullMarked") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (!type.getPackage().isAnnotatedWith(NullMarked.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  type, type.getName() + " is in a package without @NullMarked"));
        }
      }
    };
  }
}
