package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.List;
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
 * The command path, end to end over HTTP.
 *
 * <p>Written because the bus, the idempotency guard and the fail-closed
 * authorizer had all been unit tested and none of them had ever run a command.
 * Nothing here calls a handler directly: every assertion goes through
 * {@code POST /api/commands} with a real session cookie, because that is the path
 * an offline device replays into and the only one worth trusting.
 *
 * <p>Command ids are minted once per run rather than fixed, because
 * {@code integration.command_receipts} is deliberately not deletable by any
 * module role and a receipt from yesterday's run would answer today's first call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class CommandPathIntegrationTest {

  private static final String DISPATCHER = "cmd-dispatcher@waypoint.test";
  private static final String DRIVER = "cmd-driver@waypoint.test";
  private static final String PASSWORD = "CommandPath2026!";

  /** A Monday, so the operating calendar allows a vehicle status to be set on it. */
  private static final String OPERATING_DAY = "2027-03-01";

  private static final UUID VEHICLE_COMMAND = UUID.randomUUID();
  private static final UUID IMPORT_COMMAND = UUID.randomUUID();

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired List<CommandHandler> handlers;

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
    referenceImport.importFrom(Path.of("../data"), null);
    clearFixtures();
    accounts.createAccount(DISPATCHER, "Command Dispatcher", PASSWORD, "dispatcher");
    accounts.createAccount(DRIVER, "Command Driver", PASSWORD, "driver");
  }

  /**
   * R-IAM-03 in the other direction. The catalogue is what an administrator reads
   * when deciding what to grant, so an action with a live handler that the
   * catalogue calls unimplemented, or does not list at all, is a lie about what
   * is guarded.
   */
  @Test
  @Order(2)
  void everyHandlerHasACatalogueRowThatSaysItIsImplemented() {
    for (CommandHandler handler : handlers) {
      var row =
          database.asModule(
              ModuleRole.IAM,
              null,
              () ->
                  database.queryOne(
                      "SELECT implemented FROM iam.action_catalogue WHERE action = ?",
                      handler.action()));
      assertNotNull(
          row, handler.action() + " has a handler but no row in iam.action_catalogue");
      assertEquals(
          Boolean.TRUE,
          row.get("implemented"),
          handler.action() + " has a handler but the catalogue still calls it unimplemented");
    }
  }

  @Test
  @Order(3)
  void aCommandAppliesOnceHoweverOftenItIsRetried() throws Exception {
    String body = vehicleStatus("in_workshop", "Brake service");

    JsonNode first = mapper.readTree(send(DISPATCHER, body, 200));
    assertFalse(first.get("replayed").asBoolean(), "the first attempt must run the handler");
    assertEquals("in_workshop", first.get("result").get("status").asText());

    JsonNode second = mapper.readTree(send(DISPATCHER, body, 200));
    assertTrue(second.get("replayed").asBoolean(), "a retry must be answered from the receipt");
    assertEquals(
        first.get("result"),
        second.get("result"),
        "a replay must return the original result, with the same shape");

    assertEquals(1L, receipts(VEHICLE_COMMAND), "one command id, one receipt");
    assertEquals(
        "in_workshop",
        vehicleStatusInDatabase(),
        "the change is present exactly once and was not applied twice");
  }

  @Test
  @Order(4)
  void theSameCommandIdWithADifferentPayloadIsRejected() throws Exception {
    // Applying the second version would silently discard the first, which is
    // worse than telling the client it has a bug.
    send(DISPATCHER, vehicleStatus("available", "Back on the road"), 409);
    assertEquals(
        "in_workshop",
        vehicleStatusInDatabase(),
        "the rejected retry must not have changed anything");
  }

  @Test
  @Order(5)
  void aDriverIsDeniedAndTheDenialIsRecorded() throws Exception {
    String problem = send(DRIVER, importCommand(), 403);
    assertTrue(
        problem.contains("FORBIDDEN"), "a denial crosses the API as a problem document: " + problem);

    UUID driverId = userId(DRIVER);
    long denials =
        count(
            ModuleRole.INTEGRATION,
            "SELECT count(*) AS n FROM integration.audit_log"
                + " WHERE actor_id = ? AND action = 'reference:Import' AND decision = 'DENY'",
            driverId);
    assertEquals(1L, denials, "a 403 that leaves no trace is how an access problem stays invisible");

    assertEquals(0L, receipts(IMPORT_COMMAND), "a denied command must not leave a receipt");
  }

  @Test
  @Order(6)
  void anUnsignedCallerIsRefusedBeforeAnythingIsDecided() throws Exception {
    MvcResult result =
        http.perform(
                post("/api/commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(vehicleStatus("available", null)))
            .andReturn();
    assertEquals(401, result.getResponse().getStatus(), result.getResponse().getContentAsString());
  }

  @Test
  @Order(7)
  void anUnknownKindAndAMalformedEnvelopeReadAsTheClientsMistake() throws Exception {
    send(DISPATCHER, envelope(UUID.randomUUID().toString(), "vehicle:Teleport", "{}"), 404);
    send(DISPATCHER, envelope("not-a-uuid", "vehicle:SetDayStatus", "{}"), 422);
    send(DISPATCHER, envelope(UUID.randomUUID().toString(), "vehicle:SetDayStatus", "{}"), 422);
  }

  @Test
  @Order(8)
  void aCommandForANonOperatingDayIsRefusedWithTheRuleNamed() throws Exception {
    // 2027-03-07 is a Sunday. Nothing runs, so there is no status to set.
    String body =
        envelope(
            UUID.randomUUID().toString(),
            "vehicle:SetDayStatus",
            """
            {"vehicleId":"VEH001","serviceDate":"2027-03-07","status":"unavailable","reason":null}
            """);
    String problem = send(DISPATCHER, body, 422);
    assertTrue(problem.contains("R-FLT-04"), "the violated rule belongs in the problem: " + problem);
  }

  // ---- helpers ----

  private String send(String email, String body, int expectedStatus) throws Exception {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    MvcResult result =
        http.perform(
                post("/api/commands")
                    .cookie(new Cookie(AuthController.COOKIE, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private static String vehicleStatus(String status, String reason) {
    return envelope(
        VEHICLE_COMMAND.toString(),
        "vehicle:SetDayStatus",
        """
        {"vehicleId":"VEH001","serviceDate":"%s","status":"%s","reason":%s}
        """
            .formatted(OPERATING_DAY, status, reason == null ? "null" : "\"" + reason + "\""));
  }

  private static String importCommand() {
    return envelope(IMPORT_COMMAND.toString(), "reference:Import", "{}");
  }

  private static String envelope(String commandId, String kind, String payload) {
    return """
        {"commandId":"%s","kind":"%s","expectedVersion":null,"payload":%s,
         "clientRecordedAt":"2026-09-27T08:00:00Z"}
        """
        .formatted(commandId, kind, payload);
  }

  private long receipts(UUID commandId) {
    return count(
        ModuleRole.INTEGRATION,
        "SELECT count(*) AS n FROM integration.command_receipts WHERE command_id = ?",
        commandId);
  }

  private String vehicleStatusInDatabase() {
    return String.valueOf(
        database
            .asModule(
                ModuleRole.REF,
                null,
                () ->
                    database.queryOne(
                        "SELECT status FROM ref.vehicle_day_status"
                            + " WHERE vehicle_id = 'VEH001' AND service_date = ?::date",
                        OPERATING_DAY))
            .get("status"));
  }

  private UUID userId(String email) {
    return (UUID)
        database
            .asModule(
                ModuleRole.IAM,
                null,
                () -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
            .get("user_id");
  }

  private long count(ModuleRole role, String sql, Object... params) {
    return ((Number) database.asModule(role, null, () -> database.queryOne(sql, params)).get("n"))
        .longValue();
  }

  private void clearFixtures() {
    database.asModule(
        ModuleRole.REF,
        null,
        () -> {
          database.update(
              "DELETE FROM ref.vehicle_day_status WHERE service_date IN (?::date, '2027-03-07')",
              OPERATING_DAY);
        });
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update(
              "DELETE FROM iam.sessions WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'cmd-%')");
          database.update(
              "DELETE FROM iam.user_roles WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'cmd-%')");
          database.update("DELETE FROM iam.users WHERE email LIKE 'cmd-%'");
        });
  }
}
