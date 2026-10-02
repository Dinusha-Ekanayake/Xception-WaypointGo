package com.waypoint.dispatch.platform.audit;

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
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The audit log and the command receipt, end to end over HTTP (issue #6, POL-03).
 *
 * <p>Every command goes through {@code POST /api/commands} with a real session, and
 * every read through {@code GET /api/audit}, because those are the paths an auditor
 * and a retrying device actually use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(TestDatabase.class)
class AuditApiIntegrationTest {
  private static final String DISPATCHER = "aud-dispatcher@waypoint.test";
  private static final String AUDITOR = "aud-auditor@waypoint.test";
  private static final String PASSWORD = "AuditApi2026!";

  /** A Tuesday: an operating day, and not the day the command path test uses. */
  private static final String DAY = "2027-03-02";

  private static final String SUNDAY = "2027-03-07";

  private static final UUID FIRST = UUID.randomUUID();
  private static final UUID SECOND = UUID.randomUUID();
  private static final UUID REJECTED = UUID.randomUUID();
  private static final String CORRELATION = UUID.randomUUID().toString();

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired MeterRegistry meters;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    registry.add("app.scheduling.enabled", () -> "false");
  }

  @Test
  @Order(1)
  void bootstrap() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    database.asModule(
        ModuleRole.REF,
        null,
        () ->
            database.update(
                "DELETE FROM ref.vehicle_day_status WHERE service_date = ?::date", DAY));
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          database.update(
              "DELETE FROM iam.sessions WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'aud-%')");
          database.update(
              "DELETE FROM iam.user_roles WHERE user_id IN"
                  + " (SELECT user_id FROM iam.users WHERE email LIKE 'aud-%')");
          database.update("DELETE FROM iam.users WHERE email LIKE 'aud-%'");
        });
    accounts.createAccount(DISPATCHER, "Audit Dispatcher", PASSWORD, "dispatcher");
    accounts.createAccount(AUDITOR, "Audit Auditor", PASSWORD, "auditor");
  }

  @Test
  @Order(2)
  void aCommandLeavesItsCommandTargetAndOutcomeInTheAuditLog() throws Exception {
    send(DISPATCHER, status(FIRST, "in_workshop", "Brake service"), 200, CORRELATION);

    JsonNode row = onlyRow("commandId=" + FIRST).get("items").get(0);

    assertEquals(FIRST.toString(), row.get("commandId").asText());
    assertEquals("vehicle:SetDayStatus", row.get("action").asText());
    assertEquals("ALLOW", row.get("decision").asText());
    assertEquals("ref:vehicle", row.get("targetType").asText());
    assertEquals("VEH001", row.get("targetId").asText());
    assertEquals("in_workshop", row.get("after").get("status").asText());
    assertEquals("available", row.get("before").get("status").asText(), "no row meant available");
    assertEquals(CORRELATION, row.get("correlationId").asText(), "the request's own id, explicitly");
    assertNotNull(row.get("policyGeneration").asText());
  }

  @Test
  @Order(3)
  void theBeforeStateIsWhatTheRowHeldBeforeTheSecondChange() throws Exception {
    send(DISPATCHER, status(SECOND, "available", null), 200, null);

    JsonNode row = onlyRow("commandId=" + SECOND).get("items").get(0);

    assertEquals("in_workshop", row.get("before").get("status").asText());
    assertEquals("Brake service", row.get("before").get("reason").asText());
    assertEquals("available", row.get("after").get("status").asText());
  }

  @Test
  @Order(4)
  void theLogIsFilteredByTargetCorrelationAndDecisionAndPaginatedByKeyset() throws Exception {
    assertTrue(
        onlyRow("targetType=ref:vehicle&targetId=VEH001&action=vehicle:SetDayStatus&limit=50")
                .get("items").size() >= 2,
        "both of this test's commands target VEH001");
    // The command bus writes one row, and the vehicle handler writes its own, both
    // under the request's correlation id.
    assertTrue(onlyRow("correlationId=" + CORRELATION).get("items").size() >= 1);
    assertEquals(0, onlyRow("correlationId=" + UUID.randomUUID()).get("items").size());

    JsonNode first = get(AUDITOR, "/api/audit?targetType=ref:vehicle&targetId=VEH001&limit=1", 200);
    assertEquals(1, first.get("items").size());
    String cursor = first.get("nextCursor").asText();
    assertFalse(cursor.isBlank(), "there is a second page");
    JsonNode second =
        get(AUDITOR, "/api/audit?targetType=ref:vehicle&targetId=VEH001&limit=1&cursor=" + cursor, 200);
    assertEquals(1, second.get("items").size());
    assertTrue(
        second.get("items").get(0).get("auditId").asLong() < first.get("items").get(0).get("auditId").asLong(),
        "newest first, and the next page is older");

    get(AUDITOR, "/api/audit?cursor=not-a-cursor", 400);
    get(AUDITOR, "/api/audit?decision=MAYBE", 422);
    get(AUDITOR, "/api/audit?from=yesterday", 422);
  }

  @Test
  @Order(5)
  void aNonAuditorIsRefusedAndTheRefusalIsAudited() throws Exception {
    UUID dispatcherId = userId(DISPATCHER);

    get(DISPATCHER, "/api/audit", 403);

    JsonNode denials =
        get(
            AUDITOR,
            "/api/audit?actor=" + dispatcherId + "&decision=DENY&action=audit:Read",
            200);
    assertEquals(1, denials.get("items").size(), "a 403 that leaves no trace hides the attempt");
  }

  @Test
  @Order(6)
  void aRejectedCommandRetriedGetsTheSameRejectionWithoutRunningAgain() throws Exception {
    String body = status(REJECTED, "unavailable", null, SUNDAY);

    String first = send(DISPATCHER, body, 422, null);
    double replayedBefore = replayedCount();
    String second = send(DISPATCHER, body, 422, null);

    assertTrue(first.contains("R-FLT-04"), first);
    assertTrue(second.contains("R-FLT-04"), "the retry carries the same rule: " + second);
    assertEquals(replayedBefore + 1, replayedCount(), "the second answer came from the receipt");
    long receipts =
        ((Number)
                database
                    .asModule(
                        ModuleRole.INTEGRATION,
                        null,
                        () ->
                            database.queryOne(
                                "SELECT count(*) AS n FROM integration.command_receipts"
                                    + " WHERE command_id = ? AND result_status = 422",
                                REJECTED))
                    .get("n"))
            .longValue();
    assertEquals(1L, receipts, "one stored rejection, not one per attempt");
  }

  @Test
  @Order(7)
  void aRejectionIsNotAppliedAsADifferentPayloadEither() throws Exception {
    // Same id, different payload is a client bug, whatever the first answer was.
    send(DISPATCHER, status(REJECTED, "available", null, SUNDAY), 409, null);
  }

  @Test
  @Order(8)
  void theDecisionIsReplayedFromWhatWasRecordedAndNamesThePolicyInForce() throws Exception {
    JsonNode replay = get(AUDITOR, "/api/audit/decisions/" + FIRST, 200);

    assertEquals(FIRST.toString(), replay.get("commandId").asText());
    assertEquals(1, replay.get("auditRows").size());
    assertEquals(1, replay.get("receipts").size());
    assertEquals(200, replay.get("receipts").get(0).get("status").asInt());
    assertEquals("vehicle:SetDayStatus", replay.get("receipts").get(0).get("kind").asText());
    assertNotNull(replay.get("policyGenerationAtDecision").asText());
    boolean sawDispatcherPolicy = false;
    for (JsonNode version : replay.get("policyVersions")) {
      sawDispatcherPolicy |= version.get("policy").asText().equals("WaypointDispatcher");
    }
    assertTrue(sawDispatcherPolicy, "the dispatcher's policy governed this decision: " + replay);

    get(AUDITOR, "/api/audit/decisions/" + UUID.randomUUID(), 404);
    get(DISPATCHER, "/api/audit/decisions/" + FIRST, 403);
  }

  @Test
  @Order(9)
  void aRejectedCommandsReplayIsAvailableToTheAuditorToo() throws Exception {
    JsonNode replay = get(AUDITOR, "/api/audit/decisions/" + REJECTED, 200);

    assertEquals(422, replay.get("receipts").get(0).get("status").asInt());
    assertEquals(0, replay.get("auditRows").size(), "a rejection changed nothing, so wrote no ALLOW row");
  }

  // ---- helpers ----

  private JsonNode onlyRow(String query) throws Exception {
    return get(AUDITOR, "/api/audit?" + query, 200);
  }

  private JsonNode get(String email, String url, int expected) throws Exception {
    MvcResult result = http.perform(withSession(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), email)).andReturn();
    assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private String send(String email, String body, int expected, String correlation) throws Exception {
    MockHttpServletRequestBuilder request =
        withSession(post("/api/commands"), email)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (correlation != null) {
      request.header("X-Correlation-Id", correlation);
    }
    MvcResult result = http.perform(request).andReturn();
    assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    return result.getResponse().getContentAsString();
  }

  private MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, String email) {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    return request.cookie(new Cookie(AuthController.COOKIE, token));
  }

  private static String status(UUID commandId, String status, String reason) {
    return status(commandId, status, reason, DAY);
  }

  private static String status(UUID commandId, String status, String reason, String day) {
    return """
        {"commandId":"%s","kind":"vehicle:SetDayStatus","expectedVersion":null,
         "payload":{"vehicleId":"VEH001","serviceDate":"%s","status":"%s","reason":%s},
         "clientRecordedAt":"2026-09-27T08:00:00Z"}
        """
        .formatted(commandId, day, status, reason == null ? "null" : "\"" + reason + "\"");
  }

  private double replayedCount() {
    var counter = meters.find("waypoint.command.replayed").tag("kind", "vehicle:SetDayStatus").counter();
    return counter == null ? 0 : counter.count();
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
}
