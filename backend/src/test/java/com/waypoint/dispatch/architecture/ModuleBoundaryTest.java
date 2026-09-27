package com.waypoint.dispatch.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Guards the layout in docs/architecture/MODULES.md and docs/code-structure.md.
 *
 * <p>ArchUnit fails a rule that matches no classes, which is deliberate: a rule
 * that passes vacuously is worse than no rule. Add a module's rules when that
 * module gains its first class, not before.
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
            ROOT + ".platform..", ROOT + ".referencedata..", ROOT + ".identity..")
        .because("shared is the kernel: every module may use it, it may use no module")
        .check(production());
  }

  @Test
  void platformCarriesNoBusinessModule() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".platform..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + ".referencedata..", ROOT + ".identity..")
        .because("platform is technical infrastructure and holds no business rules")
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
  void referenceDataAndIdentityDoNotReachIntoEachOther() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".referencedata..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".identity.domain..",
            ROOT + ".identity.application..",
            ROOT + ".identity.infrastructure..")
        .because("a module imports another module's contract package and nothing else")
        .check(production());
  }

  @Test
  void platformMessagingDependsOnlyOnContracts() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".platform.messaging..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".identity.domain..",
            ROOT + ".identity.application..",
            ROOT + ".identity.infrastructure..",
            ROOT + ".referencedata.domain..",
            ROOT + ".referencedata.application..",
            ROOT + ".referencedata.infrastructure..")
        .because("the command bus routes commands; it must not know any module's internals")
        .check(production());
  }

  @Test
  void auditIsReachedThroughItsOwnComponent() {
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT + ".platform..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ROOT + ".platform.db.ModuleRole")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ROOT + ".platform.db.Database")
        .because(
            "a module reaches PostgreSQL through its own repository, which is the only place"
                + " allowed to name the seam directly")
        .allowEmptyShould(true)
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
}
