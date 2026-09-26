package com.waypoint.dispatch.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Guards the module layout documented in docs/code-structure.md. A rule here is
 * cheaper than a review comment, and it fails the build instead of decaying.
 *
 * <p>Rules marked "target" are not asserted yet because the extraction of
 * service/DispatchService is still in progress; see the migration stages in
 * docs/code-structure.md. Every rule below passes today.
 */
class ModuleBoundaryTest {
  private static final String ROOT = "com.waypoint.dispatch";

  private static JavaClasses production() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(ROOT);
  }

  @Test
  void sharedKernelDependsOnNothingInternal() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".shared..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".planning..",
            ROOT + ".ordering..",
            ROOT + ".loading..",
            ROOT + ".execution..",
            ROOT + ".receipt..",
            ROOT + ".identity..",
            ROOT + ".referencedata..",
            ROOT + ".messaging..",
            ROOT + ".query..",
            ROOT + ".platform..",
            ROOT + ".service..",
            ROOT + ".api..")
        .because("shared is the kernel: every module may use it, it may use no module")
        .check(production());
  }

  @Test
  void platformDependsOnlyOnSharedAndFrameworks() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".platform..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".planning..",
            ROOT + ".ordering..",
            ROOT + ".loading..",
            ROOT + ".execution..",
            ROOT + ".receipt..",
            ROOT + ".identity..",
            ROOT + ".messaging..",
            ROOT + ".query..",
            ROOT + ".service..",
            ROOT + ".api..")
        .because("platform is technical infrastructure and carries no business rules")
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
            "org.postgresql..")
        .because("business rules must be testable without Spring, a servlet or a database")
        .check(production());
  }

  @Test
  void referenceDataDomainDoesNotDependOnItsOwnAdapters() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".referencedata.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".referencedata.infrastructure..")
        .because("a domain model does not know which adapter loaded it")
        .check(production());
  }

  @Test
  void planningDomainDoesNotReachIntoOtherModules() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".planning.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".identity..",
            ROOT + ".ordering..",
            ROOT + ".loading..",
            ROOT + ".execution..",
            ROOT + ".receipt..",
            ROOT + ".messaging..",
            ROOT + ".query..",
            ROOT + ".service..",
            ROOT + ".api..",
            ROOT + ".platform..")
        .because("the constraint set is the core and depends on nothing but shared and reference data")
        .check(production());
  }

  @Test
  void onlyThePlatformSeamTouchesJdbcDirectly() {
    noClasses()
        .that()
        .resideOutsideOfPackages(
            ROOT + ".platform..",
            // remaining debt: DispatchService still queries directly, stage 2 of the migration
            ROOT + ".service..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
        .because("modules reach PostgreSQL through platform.db.Database, not through JdbcTemplate")
        .check(production());
  }

  @Test
  void noNewClassIsNamedService() {
    classes()
        .that()
        .haveSimpleNameEndingWith("Service")
        .should()
        .haveFullyQualifiedName(ROOT + ".service.DispatchService")
        .because(
            "the *Service name is what let one class absorb eight responsibilities; "
                + "use *Handler, *Query, *Policy or *Repository. DispatchService is the "
                + "documented exception until its extraction completes.")
        .check(production());
  }
}
