package com.waypoint.dispatch.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the module layout in docs/architecture/MODULES.md.
 *
 * <p>Business modules are discovered, not listed: every top-level package under
 * {@code com.waypoint.dispatch} other than {@code shared} and {@code platform}
 * is a module, so a new module is covered the moment it gains a class. A module
 * may reach another module only through that module's {@code contract} package.
 *
 * <p>ArchUnit fails a rule that matches no classes, which is deliberate: a rule
 * that passes vacuously is worse than no rule.
 */
class ModuleBoundaryTest {
  private static final String ROOT = "com.waypoint.dispatch";
  private static final Set<String> NOT_MODULES = Set.of("shared", "platform");
  private static final Pattern MODULE_PACKAGE =
      Pattern.compile("^com\\.waypoint\\.dispatch\\.([a-z]+)(?:\\.([a-z]+))?(?:\\..*)?$");

  private static JavaClasses production() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(ROOT);
  }

  /** The business module a class belongs to, if any. */
  private static Optional<String> moduleOf(JavaClass javaClass) {
    Matcher m = MODULE_PACKAGE.matcher(javaClass.getBaseComponentType().getPackageName());
    if (!m.matches() || NOT_MODULES.contains(m.group(1))) {
      return Optional.empty();
    }
    return Optional.of(m.group(1));
  }

  /** The layer inside a module: contract, domain, application, infrastructure or web. */
  private static Optional<String> layerOf(JavaClass javaClass) {
    Matcher m = MODULE_PACKAGE.matcher(javaClass.getBaseComponentType().getPackageName());
    return m.matches() ? Optional.ofNullable(m.group(2)) : Optional.empty();
  }

  private static final DescribedPredicate<JavaClass> IN_A_BUSINESS_MODULE =
      DescribedPredicate.describe("reside in a business module", c -> moduleOf(c).isPresent());

  @Test
  void sharedKernelDependsOnNothingInternal() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".shared..")
        .should()
        .dependOnClassesThat(
            IN_A_BUSINESS_MODULE.or(
                DescribedPredicate.describe(
                    "reside in platform", c -> c.getPackageName().startsWith(ROOT + ".platform"))))
        .because("shared is the kernel: every module may use it, it may use no module")
        .check(production());
  }

  @Test
  void platformCarriesNoBusinessModule() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".platform..")
        .should()
        .dependOnClassesThat(IN_A_BUSINESS_MODULE)
        .because(
            "platform is technical infrastructure: it reaches modules through ports they"
                + " implement, never by importing them")
        .check(production());
  }

  @Test
  void modulesReachEachOtherOnlyThroughContracts() {
    classes()
        .that(IN_A_BUSINESS_MODULE)
        .should(onlyReachOtherModulesThroughTheirContract())
        .because(
            "modules connect by event, by contract query or by port, never by importing"
                + " another module's domain, application, infrastructure or web")
        .check(production());
  }

  @Test
  void domainPackagesStayFrameworkFree() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "javax.sql..",
            "jakarta.servlet..",
            "com.zaxxer..",
            "org.postgresql..",
            "com.fasterxml..")
        .because("business rules must be testable without Spring, a servlet or a database")
        .check(production());
  }

  @Test
  void contractPackagesStayFrameworkFree() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "..contract..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.servlet..", "javax.sql..")
        .because("a contract is what other modules import; it must not drag a framework with it")
        .check(production());
  }

  @Test
  void eventsAreRecordsInAContract() {
    classes()
        .that()
        .implement(DomainEvent.class)
        .should()
        .resideInAPackage(ROOT + "..contract..")
        .andShould(beRecords())
        .because(
            "an event is a payload other modules depend on: immutable, and published in the"
                + " producer's contract")
        .check(production());
  }

  @Test
  void onlyThePlatformSeamTouchesJdbcDirectly() {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".platform..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
        .because("modules reach PostgreSQL through platform.db.Database, not through JdbcTemplate")
        .check(production());
  }

  @Test
  void onlyInfrastructureAndApplicationTouchTheDatabaseSeam() {
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + "..domain..", ROOT + "..web..", ROOT + "..contract..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ROOT + ".platform.db.Database")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ROOT + ".platform.db.ModuleRole")
        .because(
            "a domain rule must be testable with no database, and a controller must not reach"
                + " past the application layer. Repositories and handlers are where the seam"
                + " belongs")
        .check(production());
  }

  @Test
  void noClassIsNamedService() {
    noClasses()
        .should()
        .haveSimpleNameEndingWith("Service")
        .because(
            "the *Service name is what let one class absorb eight responsibilities. Use *Handler"
                + " for commands, *Query for reads, *Policy for decisions, *Repository for"
                + " persistence")
        .check(production());
  }

  private static ArchCondition<JavaClass> onlyReachOtherModulesThroughTheirContract() {
    return new ArchCondition<>("only reach other modules through their contract package") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        Optional<String> own = moduleOf(item);
        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          Optional<String> other = moduleOf(target);
          if (other.isPresent()
              && !other.equals(own)
              && !layerOf(target).map("contract"::equals).orElse(false)) {
            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> beRecords() {
    return new ArchCondition<>("be records") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        boolean record =
            item.getRawSuperclass()
                .map(superclass -> superclass.getName().equals("java.lang.Record"))
                .orElse(false);
        if (!record) {
          events.add(SimpleConditionEvent.violated(item, item.getName() + " is not a record"));
        }
      }
    };
  }
}
