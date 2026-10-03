package com.waypoint.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Offline operations, end to end over {@code /api/sync} with real sessions (issue #28).
 *
 * <p>No module has a device-side command yet, so a probe handler stands in: its payload says
 * whether to apply, conflict, refuse or fail as an outage, and it counts how often it really ran.
 * Its action is {@code sync:Read}, which the driver policy grants.
 *
 * <p>Accounts are minted per run because {@code sync.operations} is never deleted and references
 * them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class SyncIntegrationTest {

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
  private static final String DRIVER = "sync-driver-" + RUN + "@waypoint.test";
  private static final String OTHER_DRIVER = "sync-other-" + RUN + "@waypoint.test";
  private static final String DISPATCHER = "sync-dispatcher-" + RUN + "@waypoint.test";
  private static final String PASSWORD = "SyncPath2026!";
  private static final UUID DEVICE = UUID.randomUUID();

  private static final UUID FIRST = UUID.randomUUID();
  private static final UUID SECOND = UUID.randomUUID();
  private static final UUID STALE = UUID.randomUUID();
  private static final UUID REFUSED = UUID.randomUUID();
  private static final UUID OUTAGE = UUID.randomUUID();
  private static final UUID AFTER_OUTAGE = UUID.randomUUID();

  // A second device that drains completely, for review and time to drain.
  private static final UUID DEVICE_TWO = UUID.randomUUID();
  private static final UUID HELD = UUID.randomUUID();
  private static final UUID REDO = UUID.randomUUID();
  private static final UUID RESOLVE_HELD = UUID.randomUUID();
  private static final UUID HELD_TWO = UUID.randomUUID();

  static final AtomicInteger RUNS = new AtomicInteger();

  @TestConfiguration
  static class Probe {
    @Bean
    CommandHandler syncProbeHandler() {
      return new CommandHandler() {
        @Override
        public String kind() {
          return "test:SyncProbe";
        }

        @Override
        public String action() {
          return "sync:Read";
        }

        @Override
        public ModuleRole moduleRole() {
          return ModuleRole.SYNC;
        }

        @Override
        public Object handle(Actor actor, Command command) {
          RUNS.incrementAndGet();
          return switch (CommandPayload.of(command).requiredText("outcome")) {
            case "conflict" ->
                throw new DomainException(ErrorCode.VERSION_CONFLICT, "The record changed");
            case "refuse" -> throw new DomainException(ErrorCode.VALIDATION_FAILED, "Not allowed");
            case "outage" ->
                throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Warehouse down");
            default -> java.util.Map.of("ok", true);
          };
        }
      };
    }
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired MeterRegistry meters;

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
    accounts.createAccount(DRIVER, "Sync Driver", PASSWORD, "driver");
    accounts.createAccount(OTHER_DRIVER, "Other Driver", PASSWORD, "driver");
    accounts.createAccount(DISPATCHER, "Sync Dispatcher", PASSWORD, "dispatcher");
  }

  @Test
  @Order(2)
  void operationsApplyInTheOrderTheDeviceRecordedThemAndOnlyOnce() throws Exception {
    // Sent out of order on purpose: sequence decides, not position in the body.
    String batch = batch(op(2, SECOND, "apply"), op(1, FIRST, "apply"));
    JsonNode first = results(submit(DRIVER, batch, 200));
    assertEquals(FIRST.toString(), first.get(0).get("operationId").asText());
    assertEquals("APPLIED", first.get(0).get("status").asText());
    assertEquals("APPLIED", first.get(1).get("status").asText());
    assertEquals(2, RUNS.get());

    // The connection dropped before the device heard back, so it sends it all again.
    JsonNode again = results(submit(DRIVER, batch, 200));
    assertEquals("APPLIED", again.get(0).get("status").asText());
    assertTrue(again.get(0).get("replayed").asBoolean(), "a replay is answered from the record");
    assertEquals(2, RUNS.get(), "a replayed batch must not run anything twice");
  }

  @Test
  @Order(3)
  void aConflictIsHeldARefusalIsSettledAndAnOutageStopsTheBatch() throws Exception {
    String batch =
        batch(
            op(3, STALE, "conflict"),
            op(4, REFUSED, "refuse"),
            op(5, OUTAGE, "outage"),
            op(6, AFTER_OUTAGE, "apply"));
    JsonNode results = results(submit(DRIVER, batch, 200));

    assertEquals("CONFLICT", results.get(0).get("status").asText());
    assertEquals("VERSION_CONFLICT", results.get(0).get("problemCode").asText());
    assertEquals(2, results.get(0).get("rowVersion").asLong(), "the version a review must name");
    assertEquals("REJECTED", results.get(1).get("status").asText());
    assertEquals("RECEIVED", results.get(2).get("status").asText(), "an outage is not decided");
    assertEquals(3, results.size(), "nothing may overtake an operation still in flight");

    JsonNode page = mapper.readTree(read(DRIVER, 200));
    assertEquals(5, page.get("operations").size(), "the sixth was never reached");
  }

  @Test
  @Order(4)
  void anotherAccountSeesNoneOfIt() throws Exception {
    JsonNode page = mapper.readTree(read(OTHER_DRIVER, 200));
    assertEquals(0, page.get("operations").size(), "row-level security, not a filter in Java");
  }

  @Test
  @Order(5)
  void anOnlineOnlyRoleCannotSubmitOfflineWork() throws Exception {
    String problem = submit(DISPATCHER, batch(op(1, UUID.randomUUID(), "apply")), 403);
    assertTrue(problem.contains("FORBIDDEN"), problem);
  }

  @Test
  @Order(6)
  void onlyASettledOperationCanBeAcknowledged() throws Exception {
    send(DRIVER, acknowledge(FIRST, 2), 200);
    send(DRIVER, acknowledge(OUTAGE, 1), 422);
    // Someone else's operation reads as missing.
    send(OTHER_DRIVER, acknowledge(STALE, 2), 404);
  }

  @Test
  @Order(7)
  void anUnsignedDeviceIsRefused() throws Exception {
    MvcResult result =
        http.perform(
                post("/api/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batch(op(1, UUID.randomUUID(), "apply"))))
            .andReturn();
    assertEquals(401, result.getResponse().getStatus());
    assertFalse(result.getResponse().getContentAsString().isEmpty());
  }

  @Test
  @Order(8)
  void theOwnerDiscardsAHeldWriteWithAReasonAndNobodyElseCan() throws Exception {
    send(DRIVER, discard(STALE, 2, null), 422);
    send(OTHER_DRIVER, discard(STALE, 2, "Not mine"), 404);
    // D-O: a dispatcher may hold the action, but row-level security still hides the row.
    send(DISPATCHER, discard(STALE, 2, "Not theirs either"), 404);
    send(DRIVER, discard(FIRST, 3, "Applied writes stay"), 422);
    send(DRIVER, discard(STALE, 1, "Stale version"), 409);

    send(DRIVER, discard(STALE, 2, "Dispatch replanned the stop"), 200);
    send(DRIVER, discard(REFUSED, 2, "Entered by mistake"), 200);
    assertEquals("DISCARDED", statusOf(DRIVER, STALE));
    assertEquals("DISCARDED", statusOf(DRIVER, REFUSED));
    send(DRIVER, discard(STALE, 3, "Twice"), 422);
  }

  @Test
  @Order(9)
  void aConflictIsRedoneOnTheCurrentVersionAndTheTrailNamesTheRedo() throws Exception {
    JsonNode held = results(submit(DRIVER, batch(DEVICE_TWO, op(1, HELD, "conflict")), 200));
    assertEquals("CONFLICT", held.get(0).get("status").asText());
    long version = held.get(0).get("rowVersion").asLong();

    long drainedBefore = drains();
    int runsBefore = RUNS.get();
    // The redo goes first, then the review that names it: one device, one order.
    JsonNode results =
        results(
            submit(
                DRIVER,
                batch(DEVICE_TWO, op(2, REDO, "apply"), resolveOp(3, RESOLVE_HELD, HELD, REDO, version)),
                200));
    assertEquals("APPLIED", results.get(0).get("status").asText());
    assertEquals("APPLIED", results.get(1).get("status").asText(), results.toString());
    assertEquals(runsBefore + 1, RUNS.get(), "the redo ran once; the held write never ran again");
    assertEquals("RESOLVED", statusOf(DRIVER, HELD));
    assertEquals("APPLIED", statusOf(DRIVER, REDO));
    assertTrue(drains() > drainedBefore, "EXE-02: a device left with nothing undecided records time to drain");
  }

  @Test
  @Order(10)
  void aResolveMustNameARealRedoOfTheSameKindForAConflict() throws Exception {
    JsonNode held = results(submit(DRIVER, batch(DEVICE_TWO, op(4, HELD_TWO, "conflict")), 200));
    long version = held.get(0).get("rowVersion").asLong();

    send(DRIVER, resolve(HELD_TWO, HELD_TWO, version), 422);
    send(DRIVER, resolve(HELD_TWO, UUID.randomUUID(), version), 422);
    send(DRIVER, resolve(HELD_TWO, RESOLVE_HELD, version), 422);
    send(DRIVER, resolve(HELD, REDO, 3), 422);
    send(OTHER_DRIVER, resolve(HELD_TWO, REDO, version), 404);
    send(DRIVER, resolve(HELD_TWO, REDO, version - 1), 409);
    assertEquals("CONFLICT", statusOf(DRIVER, HELD_TWO), "every refusal left it held");
  }

  @Test
  @Order(11)
  void anOutageLeavesTheDeviceUndrained() throws Exception {
    long before = drains();
    results(submit(DRIVER, batch(op(20, UUID.randomUUID(), "outage")), 200));
    assertEquals(before, drains(), "a batch that stopped has not drained");
  }

  // ---- helpers ----

  private long drains() {
    Timer timer = meters.find("waypoint.sync.time_to_drain").timer();
    return timer == null ? 0 : timer.count();
  }

  private String statusOf(String email, UUID operationId) throws Exception {
    for (JsonNode operation : mapper.readTree(read(email, 200)).get("operations")) {
      if (operation.get("operationId").asText().equals(operationId.toString())) {
        return operation.get("status").asText();
      }
    }
    throw new AssertionError("no operation " + operationId);
  }

  private String submit(String email, String body, int status) throws Exception {
    return perform(email, post("/api/sync").contentType(MediaType.APPLICATION_JSON).content(body), status);
  }

  private String send(String email, String body, int status) throws Exception {
    return perform(
        email, post("/api/commands").contentType(MediaType.APPLICATION_JSON).content(body), status);
  }

  private String read(String email, int status) throws Exception {
    return perform(email, get("/api/sync"), status);
  }

  private String perform(String email, MockHttpServletRequestBuilder request, int status)
      throws Exception {
    String token = login.login(email, PASSWORD, null, "127.0.0.1");
    MvcResult result = http.perform(request.cookie(new Cookie(AuthController.COOKIE, token))).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(status, result.getResponse().getStatus(), response);
    return response;
  }

  private JsonNode results(String response) throws Exception {
    JsonNode results = mapper.readTree(response).get("results");
    assertNotNull(results, response);
    return results;
  }

  private static String batch(String... operations) {
    return batch(DEVICE, operations);
  }

  private static String batch(UUID device, String... operations) {
    return """
        {"deviceId":"%s","operations":[%s]}
        """.formatted(device, String.join(",", operations));
  }

  private static String op(long sequence, UUID id, String outcome) {
    return """
        {"sequence":%d,"command":{"commandId":"%s","kind":"test:SyncProbe","expectedVersion":7,
         "payload":{"outcome":"%s"},"clientRecordedAt":"2026-10-01T08:00:00Z"}}
        """.formatted(sequence, id, outcome);
  }

  private static String discard(UUID operationId, long version, String reason) {
    String why = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
    return """
        {"commandId":"%s","kind":"sync:Discard","expectedVersion":%d,
         "payload":{"operationId":"%s"%s},"clientRecordedAt":"2026-10-01T08:00:00Z"}
        """.formatted(UUID.randomUUID(), version, operationId, why);
  }

  private static String resolve(UUID operationId, UUID replacedBy, long version) {
    return resolveCommand(UUID.randomUUID(), operationId, replacedBy, version);
  }

  private static String resolveCommand(UUID commandId, UUID operationId, UUID replacedBy, long version) {
    return """
        {"commandId":"%s","kind":"sync:Resolve","expectedVersion":%d,
         "payload":{"operationId":"%s","replacedBy":"%s"},"clientRecordedAt":"2026-10-01T08:00:01Z"}
        """.formatted(commandId, version, operationId, replacedBy);
  }

  private static String resolveOp(long sequence, UUID commandId, UUID operationId, UUID replacedBy, long version) {
    return """
        {"sequence":%d,"command":%s}
        """.formatted(sequence, resolveCommand(commandId, operationId, replacedBy, version));
  }

  private static String acknowledge(UUID operationId, long version) {
    return """
        {"commandId":"%s","kind":"sync:Acknowledge","expectedVersion":%d,
         "payload":{"operationId":"%s"},"clientRecordedAt":"2026-10-01T08:00:00Z"}
        """.formatted(UUID.randomUUID(), version, operationId);
  }
}
