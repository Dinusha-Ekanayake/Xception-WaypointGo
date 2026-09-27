package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Administration over HTTP: accounts, scope, driver assignments and the calendar.
 *
 * <p>Every write here goes through {@code POST /api/commands} and every read
 * through a controller, which is the split the foundation claims. The assertions
 * that matter are the ones that would pass with the guards removed: a stale
 * version rejected, an overlapping driver assignment refused by the database, a
 * scope naming an outlet that does not exist refused rather than stored, and a
 * driver refused the account list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class AdministrationIntegrationTest {

  private static final String ADMIN = "adm-admin@waypoint.test";
  private static final String DRIVER = "adm-driver@waypoint.test";
  private static final String MANAGER = "adm-manager@waypoint.test";
  private static final String PASSWORD = "Administration2026!";
  private static final String NEW_PASSWORD = "Administration2026-reset!";

  /** 2027-04-04 is a Sunday, so the network does not operate on it by policy. */
  private static final LocalDate CLOSED_SUNDAY = LocalDate.parse("2027-04-04");

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired SessionRegistry sessions;
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
  void bootstrap() {
    migrator.migrate();
    // Before the import, not after: the import is what rebuilds the in-memory
    // snapshot, and a calendar override left by an earlier run would otherwise
    // still be in it when the first assertion reads a closed Sunday.
    clearFixtures();
    referenceImport.importFrom(Path.of("../data"), null);
    accounts.createAccount(ADMIN, "Administration Admin", PASSWORD, "admin");
    accounts.createAccount(DRIVER, "Administration Driver", PASSWORD, "driver");
    accounts.createAccount(MANAGER, "Administration Manager", PASSWORD, "store_manager");
  }

  @Test
  @Order(2)
  void anAdminCreatesAnAccountThroughTheCommandEndpointAndReadsItBack() throws Exception {
    JsonNode created =
        ack(
            command(
                ADMIN,
                "iam:CreateUser",
                null,
                """
                {"email":"adm-new@waypoint.test","displayName":"Newly Created",
                 "password":"CreatedByCommand2026!","roleCode":"loader"}
                """,
                200));
    String userId = created.get("result").get("userId").asText();

    JsonNode read = mapper.readTree(read(ADMIN, "/api/accounts/" + userId, 200));
    assertEquals("adm-new@waypoint.test", read.get("email").asText());
    assertEquals("loader", read.get("roles").get(0).asText());
    assertTrue(read.get("active").asBoolean());
    assertEquals(
        1,
        read.get("rowVersion").asLong(),
        "the read surface has to expose the version, or no caller can send expectedVersion");
  }

  @Test
  @Order(3)
  void anUpdateNeedsTheCurrentVersionAndAStaleOneIsRefused() throws Exception {
    String userId = userIdOf("adm-new@waypoint.test").toString();
    long version = versionOf(userId);

    ack(
        command(
            ADMIN,
            "iam:UpdateUser",
            version,
            """
            {"userId":"%s","displayName":"Renamed By Command"}
            """
                .formatted(userId),
            200));
    assertEquals(
        "Renamed By Command",
        mapper.readTree(read(ADMIN, "/api/accounts/" + userId, 200)).get("displayName").asText());
    assertEquals(version + 1, versionOf(userId), "a change bumps the row version");

    // The same version again is a second writer working from a stale read.
    command(
        ADMIN,
        "iam:UpdateUser",
        version,
        """
        {"userId":"%s","displayName":"Renamed Again"}
        """
            .formatted(userId),
        409);
  }

  @Test
  @Order(4)
  void resettingAPasswordReplacesItAndRevokesEverySession() throws Exception {
    String token = login.login("adm-new@waypoint.test", "CreatedByCommand2026!", null, "127.0.0.1");
    assertTrue(sessions.resolve(token).isPresent(), "the session should start valid");

    String userId = userIdOf("adm-new@waypoint.test").toString();
    ack(
        command(
            ADMIN,
            "iam:ResetPassword",
            versionOf(userId),
            """
            {"userId":"%s","password":"%s"}
            """
                .formatted(userId, NEW_PASSWORD),
            200));

    assertTrue(
        sessions.resolve(token).isEmpty(),
        "the reason to reset is usually that someone else has the account");
    // The new password works and the old one does not.
    login.login("adm-new@waypoint.test", NEW_PASSWORD, null, "127.0.0.1");
    org.junit.jupiter.api.Assertions.assertThrows(
        com.waypoint.dispatch.shared.error.DomainException.class,
        () -> login.login("adm-new@waypoint.test", "CreatedByCommand2026!", null, "127.0.0.1"));
  }

  @Test
  @Order(5)
  void anOutletScopeIsGrantedRevokedAndRefusedWhenTheOutletDoesNotExist() throws Exception {
    String userId = userIdOf(MANAGER).toString();

    ack(
        command(
            ADMIN,
            "iam:GrantScope",
            null,
            """
            {"userId":"%s","outletId":"OUT001"}
            """.formatted(userId),
            200));
    assertEquals(
        "OUT001",
        mapper.readTree(read(ADMIN, "/api/accounts/" + userId, 200)).get("outlets").get(0).asText());

    // A scope naming something that does not exist is a permanent silent denial,
    // which is the same class of mistake as an action missing from the catalogue.
    command(
        ADMIN,
        "iam:GrantScope",
        null,
        """
        {"userId":"%s","outletId":"OUT999"}
        """.formatted(userId),
        404);

    // Both or neither is ambiguous about what was actually granted.
    command(
        ADMIN,
        "iam:GrantScope",
        null,
        """
        {"userId":"%s","outletId":"OUT001","depotCode":"Peliyagoda"}
        """.formatted(userId),
        422);

    ack(
        command(
            ADMIN,
            "iam:RevokeScope",
            null,
            """
            {"userId":"%s","outletId":"OUT001"}
            """.formatted(userId),
            200));
    assertEquals(
        0,
        mapper.readTree(read(ADMIN, "/api/accounts/" + userId, 200)).get("outlets").size(),
        "the grant is gone, not merely hidden");
  }

  @Test
  @Order(6)
  void twoDriversCannotHoldOneVehicleOverTheSameDays() throws Exception {
    String driverId = userIdOf(DRIVER).toString();
    String otherDriverId = userIdOf("adm-second-driver@waypoint.test", true).toString();

    JsonNode first =
        ack(
            command(
                ADMIN,
                "iam:AssignDriver",
                null,
                """
                {"vehicleId":"VEH002","driverUserId":"%s","from":"2027-05-03","until":"2027-05-10"}
                """
                    .formatted(driverId),
                200));
    String assignmentId = first.get("result").get("assignmentId").asText();

    // The exclusion constraint decides this, not a check followed by an insert:
    // a check plus an insert is a race, a constraint is not.
    String problem =
        command(
            ADMIN,
            "iam:AssignDriver",
            null,
            """
            {"vehicleId":"VEH002","driverUserId":"%s","from":"2027-05-05","until":"2027-05-12"}
            """
                .formatted(otherDriverId),
            409);
    assertTrue(problem.contains("R-IAM-13"), problem);

    // Abutting the day the first one ends is not an overlap: the range is half open.
    ack(
        command(
            ADMIN,
            "iam:AssignDriver",
            null,
            """
            {"vehicleId":"VEH002","driverUserId":"%s","from":"2027-05-10","until":"2027-05-17"}
            """
                .formatted(otherDriverId),
            200));

    // Releasing early shortens the assignment rather than deleting it, so who held
    // the vehicle stays answerable.
    ack(
        command(
            ADMIN,
            "iam:EndDriverAssignment",
            null,
            """
            {"assignmentId":"%s","on":"2027-05-06"}
            """.formatted(assignmentId),
            200));
    JsonNode assignments = mapper.readTree(read(ADMIN, "/api/accounts/driver-assignments?on=2027-05-07", 200));
    assertEquals(0, assignments.size(), "the released days are no longer held by anyone");
  }

  @Test
  @Order(7)
  void anAccountWithNoDriverRoleCannotHoldAVehicle() throws Exception {
    command(
        ADMIN,
        "iam:AssignDriver",
        null,
        """
        {"vehicleId":"VEH003","driverUserId":"%s","from":"2027-06-01","until":"2027-06-08"}
        """
            .formatted(userIdOf(MANAGER)),
        422);
  }

  /**
   * The point of the override: it has to reach the in-memory snapshot, or nothing
   * that reads reference data will ever see it.
   */
  @Test
  @Order(8)
  void overridingACalendarDayChangesWhatEveryReaderSees() throws Exception {
    assertFalse(reference.isOperating(CLOSED_SUNDAY), "a Sunday does not operate by policy");

    // Without an override the vehicle status command refuses the day.
    command(
        ADMIN,
        "vehicle:SetDayStatus",
        null,
        """
        {"vehicleId":"VEH004","serviceDate":"%s","status":"unavailable"}
        """.formatted(CLOSED_SUNDAY),
        422);

    ack(
        command(
            ADMIN,
            "calendar:Override",
            null,
            """
            {"date":"%s","operating":true,"reason":"Festival moved; the network works this Sunday"}
            """
                .formatted(CLOSED_SUNDAY),
            200));

    assertTrue(
        reference.isOperating(CLOSED_SUNDAY),
        "an override that does not reach the snapshot is an override nothing reads");
    JsonNode day = mapper.readTree(read(ADMIN, "/api/reference/calendar/" + CLOSED_SUNDAY, 200));
    assertTrue(day.get("operating").asBoolean());
    assertFalse(
        day.get("day").get("generated").asBoolean(),
        "a person decided this day, so it is not an assumed one");

    // And the command that was refused now succeeds.
    ack(
        command(
            ADMIN,
            "vehicle:SetDayStatus",
            null,
            """
            {"vehicleId":"VEH004","serviceDate":"%s","status":"unavailable"}
            """.formatted(CLOSED_SUNDAY),
            200));
  }

  @Test
  @Order(9)
  void anOverrideWithoutAReasonIsRefused() throws Exception {
    command(
        ADMIN,
        "calendar:Override",
        null,
        """
        {"date":"2027-04-11","operating":true}
        """,
        422);
  }

  @Test
  @Order(10)
  void readEndpointsAreAPermissionAndADriverDoesNotHaveThisOne() throws Exception {
    // A driver may read reference data, which is what a route needs.
    assertTrue(
        mapper.readTree(read(DRIVER, "/api/reference/outlets/OUT001", 200)).has("brandCode"));

    // A driver may not read the account list, and the refusal is a 403 with a
    // reason, never an empty list.
    String problem = read(DRIVER, "/api/accounts", 403);
    assertTrue(problem.contains("FORBIDDEN"), problem);

    // A store manager has no reference:Read in the seeded policy.
    read(MANAGER, "/api/reference/version", 403);

    // And an unsigned caller gets 401 rather than a hint about what exists.
    MvcResult anonymous = http.perform(get("/api/reference/version")).andReturn();
    assertEquals(401, anonymous.getResponse().getStatus());
  }

  @Test
  @Order(11)
  void anAccountCannotDisableItselfAndDisablingRevokesSessions() throws Exception {
    String adminId = userIdOf(ADMIN).toString();
    command(
        ADMIN,
        "iam:DisableUser",
        versionOf(adminId),
        """
        {"userId":"%s"}
        """.formatted(adminId),
        422);

    String targetId = userIdOf("adm-new@waypoint.test").toString();
    String token = login.login("adm-new@waypoint.test", NEW_PASSWORD, null, "127.0.0.1");
    JsonNode disabled =
        ack(
            command(
                ADMIN,
                "iam:DisableUser",
                versionOf(targetId),
                """
                {"userId":"%s"}
                """.formatted(targetId),
                200));

    assertTrue(disabled.get("result").get("sessionsRevoked").asInt() >= 1);
    assertTrue(sessions.resolve(token).isEmpty());
    assertFalse(
        mapper.readTree(read(ADMIN, "/api/accounts/" + targetId, 200)).get("active").asBoolean());
  }

  // ---- helpers ----

  private String command(
      String email, String kind, Long expectedVersion, String payload, int expectedStatus)
      throws Exception {
    String body =
        """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-09-28T08:00:00Z"}
        """
            .formatted(
                UUID.randomUUID(),
                kind,
                expectedVersion == null ? "null" : expectedVersion.toString(),
                payload);
    return perform(post("/api/commands").content(body), email, expectedStatus);
  }

  private String read(String email, String path, int expectedStatus) throws Exception {
    return perform(get(path), email, expectedStatus);
  }

  private String perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
      String email,
      int expectedStatus)
      throws Exception {
    String token = login.login(email, passwordFor(email), null, "127.0.0.1");
    MvcResult result =
        http.perform(
                builder
                    .cookie(new Cookie(AuthController.COOKIE, token))
                    .contentType(MediaType.APPLICATION_JSON))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private static String passwordFor(String email) {
    return "adm-new@waypoint.test".equals(email) ? NEW_PASSWORD : PASSWORD;
  }

  private JsonNode ack(String response) throws Exception {
    JsonNode ack = mapper.readTree(response);
    assertFalse(ack.get("replayed").asBoolean(), "each command here uses a fresh id");
    return ack;
  }

  private UUID userIdOf(String email) {
    return userIdOf(email, false);
  }

  private UUID userIdOf(String email, boolean createDriverIfMissing) {
    UUID id =
        (UUID)
            java.util.Optional.ofNullable(
                    database.asModule(
                        ModuleRole.IAM,
                        null,
                        () ->
                            database.queryOne(
                                "SELECT user_id FROM iam.users WHERE email = ?", email)))
                .map(row -> row.get("user_id"))
                .orElse(null);
    if (id == null && createDriverIfMissing) {
      return accounts.createAccount(email, "Second Driver", PASSWORD, "driver");
    }
    return id;
  }

  private long versionOf(String userId) throws Exception {
    return mapper.readTree(read(ADMIN, "/api/accounts/" + userId, 200)).get("rowVersion").asLong();
  }

  private void clearFixtures() {
    database.asModule(
        ModuleRole.REF,
        null,
        () -> {
          database.update(
              "DELETE FROM ref.calendar_overrides WHERE calendar_date IN (?::date, '2027-04-11')",
              java.sql.Date.valueOf(CLOSED_SUNDAY));
          database.update(
              "DELETE FROM ref.vehicle_day_status WHERE service_date = ?::date",
              java.sql.Date.valueOf(CLOSED_SUNDAY));
        });
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update(
              "DELETE FROM iam.vehicle_driver_assignments WHERE driver_user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'adm-%')");
          database.update(
              "DELETE FROM iam.user_outlet_access WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'adm-%')");
          database.update(
              "DELETE FROM iam.user_depot_access WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'adm-%')");
          database.update(
              "DELETE FROM iam.sessions WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'adm-%')");
          database.update(
              "DELETE FROM iam.user_roles WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'adm-%')");
          database.update("DELETE FROM iam.users WHERE email LIKE 'adm-%'");
        });
  }
}
