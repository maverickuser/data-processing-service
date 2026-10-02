package com.bondplatform.dataprocessing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The project's structural rules, from docs/engineering/java-standards.md.
 *
 * <p>Each rule is a constant so that one test applies it to production code and another proves it
 * rejects a deliberately wrong fixture.
 */
final class ArchitectureRules {

  private static final String DOMAIN = "..domain..";
  private static final String ADAPTER = "..adapter..";
  private static final String LAMBDA = "..dataprocessing.lambda..";
  private static final String SUPPLIER = "..shared.supplier..";

  /** U-ARCH-01: domain code is plain Java, free of framework, AWS SDK, and JDBC types. */
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
              "java.sql..",
              "javax.sql..")
          .allowEmptyShould(true);

  /** U-ARCH-02: only adapters and Lambda entry points may depend on adapters. */
  static final ArchRule ONLY_ADAPTERS_DEPEND_ON_ADAPTERS =
      noClasses()
          .that()
          .resideOutsideOfPackages(ADAPTER, LAMBDA)
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ADAPTER)
          .allowEmptyShould(true);

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

  /** U-ARCH-03: no floating-point fields; source numbers are exact. */
  static final ArchRule NO_FLOATING_POINT_FIELDS =
      fields().should(notBeFloatingPoint()).allowEmptyShould(true);

  /** U-ARCH-03: no floating-point parameters or return values. */
  static final ArchRule NO_FLOATING_POINT_METHODS =
      methods().should(notUseFloatingPoint()).allowEmptyShould(true);

  /** U-ARCH-03: time and identifiers come from injected suppliers, never from the system. */
  static final ArchRule TIME_AND_IDS_ARE_INJECTED =
      noClasses()
          .that()
          .resideOutsideOfPackage(SUPPLIER)
          .should()
          .callMethod(Instant.class, "now")
          .orShould()
          .callMethod(LocalDate.class, "now")
          .orShould()
          .callMethod(LocalDateTime.class, "now")
          .orShould()
          .callMethod(UUID.class, "randomUUID")
          .allowEmptyShould(true);

  private ArchitectureRules() {}

  private static final DescribedPredicate<JavaClass> FLOATING_POINT =
      DescribedPredicate.describe(
          "floating point",
          type ->
              type.isEquivalentTo(float.class)
                  || type.isEquivalentTo(double.class)
                  || type.isEquivalentTo(Float.class)
                  || type.isEquivalentTo(Double.class));

  private static ArchCondition<com.tngtech.archunit.core.domain.JavaField> notBeFloatingPoint() {
    return new ArchCondition<>("not be floating point") {
      @Override
      public void check(com.tngtech.archunit.core.domain.JavaField field, ConditionEvents events) {
        if (FLOATING_POINT.test(field.getRawType())) {
          events.add(
              SimpleConditionEvent.violated(
                  field, field.getFullName() + " is a floating-point field"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> notUseFloatingPoint() {
    return new ArchCondition<>("not take or return floating point") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean usesFloatingPoint =
            FLOATING_POINT.test(method.getRawReturnType())
                || method.getRawParameterTypes().stream().anyMatch(FLOATING_POINT);
        if (usesFloatingPoint) {
          events.add(
              SimpleConditionEvent.violated(
                  method, method.getFullName() + " takes or returns floating point"));
        }
      }
    };
  }
}
