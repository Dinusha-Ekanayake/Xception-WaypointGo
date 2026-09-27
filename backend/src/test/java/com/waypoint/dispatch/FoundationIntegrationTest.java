package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.PolicyAdminUseCase;
import com.waypoint.dispatch.identity.application.PolicyDecisionPoint;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the unit tests cannot prove: that the pieces work together against a real
 * PostgreSQL, with real roles, real row-level security and real Argon2 hashing.
 *
 * <p>Runs only when {@code TEST_DATABASE_URL} names a dedicated database, and
 * never against the application database. Row-level security and database role
 * separation cannot be faked by a mock, so there is no value in pretending.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class FoundationIntegrationTest {

  private static final String ADMIN = "it-admin@waypoint.test";
  private static final String DRIVER = "it-driver@waypoint.test";
  private static final String PASSWORD = "IntegrationTest2026!";

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired SessionRegistry sessions;
  @Autowired PolicyDecisionPoint decisions;
  @Autowired PolicyAdminUseCase policies;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", () -> System.getenv("TEST_DATABASE_URL"));
  }

  @BeforeAll
  static void guardAgainstTheApplicationDatabase() {
    String url = System.getenv("TEST_DATABASE_URL");
    String application = System.getenv("DATABASE_URL");
    if (application != null && application.equals(url)) {
      throw new IllegalStateException(
          "TEST_DATABASE_URL must differ from DATABASE_URL; tests destroy data");
    }
  }

  @Test
  @Order(1)
  void migrationsApplyAndAreIdempotent() {
    migrator.migrate();
    assertEquals(0, migrator.migrate(), "a second run must apply nothing");
  }

  @Test
  @Order(2)
  void referenceImportPublishesAndReImportIsANoOp() {
    clearAccounts();
    var first = referenceImport.importFrom(Path.of("../data"), null);
    assertTrue(first.published());
    assertEquals(120, first.outlets());
    assertEquals(60, first.vehicles());

    var second = referenceImport.importFrom(Path.of("../data"), null);
    assertFalse(second.published(), "identical content must not mint a new version");
    assertEquals(first.versionId(), second.versionId());
  }

  @Test
  @Order(3)
  void referenceIsReadableThroughTheContract() {
    var outlet = reference.outlet("OUT001", null).orElseThrow();
    assertEquals("Fresh", outlet.brandCode());
    assertTrue(outlet.vanOnly(), "OUT001 is van_only in the supplied data");
    assertTrue(reference.vehicle("VEH001", null).orElseThrow().refrigerated());
  }

  @Test
  @Order(4)
  void signingInRequiresTheRightPasswordAndRevealsNothingOtherwise() {
    UUID adminId = accounts.createAccount(ADMIN, "IT Admin", PASSWORD, "admin");
    accounts.createAccount(DRIVER, "IT Driver", PASSWORD, "driver");

    DomainException wrongPassword =
        assertThrows(DomainException.class, () -> login.login(ADMIN, "not-it", null, "127.0.0.1"));
    DomainException unknownAccount =
        assertThrows(
            DomainException.class, () -> login.login("nobody@waypoint.test", "x", null, "127.0.0.1"));

    assertEquals(
        wrongPassword.getMessage(),
        unknownAccount.getMessage(),
        "a wrong password and an unknown account must be indistinguishable");
    assertEquals(ErrorCode.UNAUTHENTICATED, wrongPassword.code());

    String token = login.login(ADMIN, PASSWORD, null, "127.0.0.1");
    var session = sessions.resolve(token).orElseThrow();
    assertEquals(adminId, session.userId());
    assertEquals("admin", session.roles().get(0));
  }

  @Test
  @Order(5)
  void passwordsAreStoredAsArgon2NotPlaintext() {
    String hash =
        String.valueOf(
            database
                .asModule(
                    ModuleRole.IAM,
                    null,
                    () ->
                        database.queryOne(
                            "SELECT password_hash FROM iam.users WHERE email = ?", ADMIN))
                .get("password_hash"));

    assertTrue(hash.startsWith("$argon2id$"), hash);
    assertNotEquals(PASSWORD, hash);
  }

  @Test
  @Order(6)
  void seededRolePoliciesDecideDifferently() {
    Actor admin = actorFor(ADMIN);
    Actor driver = actorFor(DRIVER);

    assertTrue(decisions.decide(admin, "reference:Import", "*", Map.of()).allowed());
    assertFalse(decisions.decide(driver, "reference:Import", "*", Map.of()).allowed());
    assertTrue(decisions.decide(driver, "delivery:Record", "*", Map.of()).allowed());
  }

  /** The acceptance criterion: permissions change at runtime, with no deployment. */
  @Test
  @Order(7)
  void anAdminChangesAPolicyAndTheDecisionChangesImmediately() {
    Actor admin = actorFor(ADMIN);
    Actor driver = actorFor(DRIVER);

    assertFalse(
        decisions.decide(driver, "plan:Publish", "*", Map.of()).allowed(),
        "a driver must not publish plans to begin with");

    policies.createPolicy(
        admin,
        "ItDriverMayPublish",
        "Integration test grant",
        """
        {"Version":"test","Statement":[
          {"Sid":"LetDriverPublish","Effect":"Allow","Action":["plan:Publish"],"Resource":["*"]}]}
        """);
    policies.attach(admin, "ItDriverMayPublish", "role", "driver");

    assertTrue(
        decisions.decide(driver, "plan:Publish", "*", Map.of()).allowed(),
        "the change must take effect on the next decision, with no restart");

    policies.detach(admin, "ItDriverMayPublish", "role", "driver");
    assertFalse(
        decisions.decide(driver, "plan:Publish", "*", Map.of()).allowed(),
        "detaching must take effect just as immediately");
  }

  @Test
  @Order(8)
  void aPolicyNamingAnUnknownActionIsRejectedWhenWritten() {
    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                policies.createPolicy(
                    actorFor(ADMIN),
                    "ItTypoPolicy",
                    "Contains a typo",
                    """
                    {"Version":"test","Statement":[
                      {"Sid":"Typo","Effect":"Allow","Action":["order:Plase"],"Resource":["*"]}]}
                    """));

    assertEquals(ErrorCode.VALIDATION_FAILED, thrown.code());
    assertTrue(thrown.violations().contains("order:Plase"), thrown.violations().toString());
  }

  @Test
  @Order(9)
  void rowLevelSecurityReturnsNothingWhenNoActorIsSet() {
    accounts.grantDepot(ADMIN, "Peliyagoda");

    long withoutActor =
        ((Number)
                database
                    .asModule(
                        ModuleRole.OPS,
                        null,
                        () ->
                            database.queryOne(
                                "SELECT count(*) AS visible FROM iam.user_depot_access"))
                    .get("visible"))
            .longValue();

    assertEquals(0, withoutActor, "an unset actor must see no rows, not every row");

    Actor admin = actorFor(ADMIN);
    long withActor =
        ((Number)
                database
                    .asModule(
                        ModuleRole.OPS,
                        admin.userId(),
                        () ->
                            database.queryOne(
                                "SELECT count(*) AS visible FROM iam.user_depot_access"))
                    .get("visible"))
            .longValue();

    assertEquals(1, withActor, "the actor must see exactly their own grant");
  }

  @Test
  @Order(10)
  void disablingAnAccountRevokesItsSessionsAtOnce() {
    String token = login.login(DRIVER, PASSWORD, null, "127.0.0.1");
    assertTrue(sessions.resolve(token).isPresent(), "the session should start valid");

    accounts.disableAccount(DRIVER);

    assertTrue(
        sessions.resolve(token).isEmpty(),
        "a disabled account must not keep a live session until it expires");
  }

  // ---- helpers ----

  private Actor actorFor(String email) {
    UUID id =
        (UUID)
            database
                .asModule(
                    ModuleRole.IAM,
                    null,
                    () -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
                .get("user_id");
    return Actor.user(id);
  }

  private void clearAccounts() {
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update("DELETE FROM iam.policy_attachments WHERE principal_type = 'role'"
              + " AND policy_id IN (SELECT policy_id FROM iam.policies WHERE name LIKE 'It%')");
          database.update("DELETE FROM iam.policies WHERE name LIKE 'It%'");
          database.update("DELETE FROM iam.user_depot_access");
          database.update("DELETE FROM iam.sessions");
          database.update("DELETE FROM iam.user_roles WHERE user_id IN"
              + " (SELECT user_id FROM iam.users WHERE email LIKE 'it-%')");
          database.update("DELETE FROM iam.users WHERE email LIKE 'it-%'");
        });
  }
}
