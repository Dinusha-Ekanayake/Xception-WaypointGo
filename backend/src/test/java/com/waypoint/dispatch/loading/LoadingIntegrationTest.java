package com.waypoint.dispatch.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.loading.LoadingTestConfig.MovableClock;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.ConsumerInbox;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Loading end to end: a published plan builds the manifest, and take, check,
 * flag, hand back and release go through POST /api/commands with the trip's
 * version moving on by exactly one per command.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LoadingTestConfig.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class LoadingIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String OTHER_DEPOT_OUTLET = "OUT076";
  static final String PASSWORD = "LoadingDock2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired OperatorRegistry operators;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired MovableClock clock;
  @Autowired ConsumerInbox inbox;
  @Autowired List<EventSubscriber<?>> subscribers;

  String depot;
  LocalDate serviceDate;
  Cookie manager;
  Cookie isuru;
  Cookie kasun;
  Cookie outsider;
  UUID isuruId;
  UUID kasunId;
  UUID outsiderId;
  UUID planId;
  UUID tripId;
  String vehicleId;
  UUID orderA;
  UUID orderB;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", () -> System.getenv("TEST_DATABASE_URL"));
  }

  @BeforeAll
  static void guardAgainstTheApplicationDatabase() {
    String application = System.getenv("DATABASE_URL");
    if (application != null && application.equals(System.getenv("TEST_DATABASE_URL"))) {
      throw new IllegalStateException("TEST_DATABASE_URL must differ from DATABASE_URL");
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    clock.reset();
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    depot = reference.outlet(OUTLET, null).orElseThrow().depotCode();
    serviceDate =
        reference.nextOperatingDay(
            LocalDate.of(2040, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));
    clock.set(serviceDate.minusDays(1).atTime(LocalTime.of(10, 0)).atZone(Clock.OPERATING_ZONE).toInstant());

    String run = UUID.randomUUID().toString().substring(0, 8);
    String managerEmail = "lm-" + run + "@loading.test";
    UUID managerId = accounts.createAccount(managerEmail, "Store", PASSWORD, "store_manager");
    database.asModule(ModuleRole.IAM, null, () -> database.update(
        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)", managerId, OUTLET));
    String isuruEmail = "li-" + run + "@loading.test";
    String kasunEmail = "lk-" + run + "@loading.test";
    String outsiderEmail = "lo-" + run + "@loading.test";
    isuruId = accounts.createAccount(isuruEmail, "Isuru Sudarshana", PASSWORD, "loader");
    kasunId = accounts.createAccount(kasunEmail, "Kasun Rathnayake", PASSWORD, "loader");
    outsiderId = accounts.createAccount(outsiderEmail, "No Depot", PASSWORD, "loader");
    accounts.grantDepot(isuruEmail, depot);
    accounts.grantDepot(kasunEmail, depot);
    String otherDepot = reference.outlet(OTHER_DEPOT_OUTLET, null).orElseThrow().depotCode();
    accounts.grantDepot(outsiderEmail, otherDepot);
    operators.setPin(isuruEmail, "1357", null);
    operators.setPin(kasunEmail, "2468", null);
    operators.setPin(outsiderEmail, "8642", null);
    manager = session(managerEmail);
    isuru = session(isuruEmail);
    kasun = session(kasunEmail);
    outsider = session(outsiderEmail);
    switchOperator(isuru, isuruId, "1357");
    switchOperator(kasun, kasunId, "2468");
    switchOperator(outsider, outsiderId, "8642");

    orderA = place("[{\"productId\":\"P-1\",\"quantity\":2},{\"productId\":\"P-2\",\"quantity\":3}]");
    orderB = place("[{\"productId\":\"P-3\",\"quantity\":4}]");
    vehicleId = reference.availableVehicles(depot, serviceDate, null).get(0).vehicleId();
    planId = UUID.randomUUID();
    tripId = UUID.randomUUID();
    deliver("loading.on-plan-published", new PlanPublished(planId, depot, serviceDate, 1, Optional.empty(),
        List.of(trip(List.of(stop(1, orderA), stop(2, orderB))))));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aPublishedPlanBuildsAManifestInReverseStopOrder() throws Exception {
    JsonNode board = json(read(isuru, "/api/loading/trips?depot=" + depot + "&date=" + serviceDate, 200));
    JsonNode card = find(board, tripId);
    assertEquals("NOT_STARTED", card.get("status").asText());
    assertEquals(2, card.get("orderCount").asInt());
    assertTrue(card.get("dockCode").asText().startsWith("Dock "));

    JsonNode manifest = manifest(isuru);
    assertEquals(1, manifest.get("rowVersion").asLong());
    assertEquals(orderB.toString(), manifest.get("lines").get(0).get("orderId").asText(), "D-L: last stop first");
    assertEquals(2, manifest.get("lines").get(1).get("items").size(), "one item per order line");
  }

  @Test
  void aDepotOutsideTheLoadersScopeIsForbiddenNotEmpty() throws Exception {
    read(outsider, "/api/loading/trips?depot=" + depot + "&date=" + serviceDate, 403);
    read(outsider, "/api/loading/trips/" + tripId + "/manifest", 404);
    assertTrue(audited("loading:Read", "DENY"), "SEC-04: the denial is audited");
  }

  @Test
  void removedLoadingIssueTypesAreRejectedWithoutChangingTheTrip() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Shortfall", 2L,
        "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderA
            + "\",\"lineNo\":1,\"kind\":\"SHORT\",\"missingUnits\":1,\"reason\":\"Not offered\"}", 422);
    send(isuru, "loading:Shortfall", 2L,
        "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderA
            + "\",\"lineNo\":1,\"kind\":\"WRONG_TEMP\",\"missingUnits\":1,\"reason\":\"Not offered\"}", 422);
    assertEquals(2, manifest(isuru).get("rowVersion").asLong());
  }

  @Test
  void anOutOfScopeWriteIsForbiddenAndAuditedBeforeVersionChecking() throws Exception {
    send(outsider, "loading:Start", 999L, "{\"tripId\":\"" + tripId + "\"}", 403);
    assertTrue(database.asModule(ModuleRole.INTEGRATION, null, () -> !database.query(
        "SELECT 1 FROM integration.audit_log WHERE action = ? AND decision = ? AND resource = ?",
        "loading:Start", "DENY", "wpt:loading:trip:" + tripId).isEmpty()));
    assertEquals(1, manifest(isuru).get("rowVersion").asLong());
  }

  @Test
  void aVehicleChangeRequiresLoadingTheItemsAgain() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200);
    String replacement = reference.availableVehicles(depot, serviceDate, null).stream()
        .filter(v -> !v.vehicleId().equals(vehicleId)).findFirst().orElseThrow().vehicleId();
    PlannedTrip revised = new PlannedTrip(tripId, replacement, 1, "Fresh", "Kandy", "ambient",
        LocalTime.of(4, 30), List.of(stop(1, orderA), stop(2, orderB)));
    deliver("loading.on-plan-revised", new PlanRevised(planId, depot, serviceDate, 2, planId,
        "vehicle unavailable", List.of(revised)));
    JsonNode m = manifest(isuru);
    assertEquals(4, m.get("rowVersion").asLong());
    assertEquals("PENDING", line(m, orderA).get("items").get(0).get("status").asText());
    assertEquals(replacement, m.get("vehicleId").asText());
  }

  @Test
  void aReorderedStopRequiresLoadingTheItemsAgain() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200);
    deliver("loading.on-plan-revised", new PlanRevised(planId, depot, serviceDate, 2, planId,
        "stop sequence changed", List.of(trip(List.of(stop(2, orderA), stop(1, orderB))))));
    JsonNode m = manifest(isuru);
    assertEquals(4, m.get("rowVersion").asLong());
    assertEquals("PENDING", line(m, orderA).get("items").get(0).get("status").asText());
  }

  @Test
  void eachCommandMovesTheVersionOnByExactlyOne() throws Exception {
    assertEquals(2, result(send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200)));
    assertEquals(3, result(send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200)));
    assertEquals(4, result(send(isuru, "loading:Check", 3L, check(orderA, 1, "PENDING"), 200)));
    assertEquals(4, manifest(isuru).get("rowVersion").asLong());
    assertEquals(1, outbox("loading.started"), "loading.started is announced once");
  }

  @Test
  void aStaleVersionIsAConflictAndAReplayIsAnswered() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    String stale = send(isuru, "loading:Check", 1L, check(orderA, 1, "LOADED"), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);

    UUID id = UUID.randomUUID();
    String body = envelope(id, "loading:Check", 2L, check(orderA, 1, "LOADED"));
    JsonNode first = json(command(isuru, body, 200));
    JsonNode again = json(command(isuru, body, 200));
    assertTrue(again.get("replayed").asBoolean());
    assertEquals(first.get("result"), again.get("result"));
  }

  @Test
  void oneLoaderPerTripUntilHandBack() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    String taken = send(kasun, "loading:Start", 2L, "{\"tripId\":\"" + tripId + "\"}", 409);
    assertTrue(taken.contains("R-LOD-11") && taken.contains("Isuru"), taken);
    send(kasun, "loading:Check", 2L, check(orderA, 1, "LOADED"), 409);

    send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200);
    send(isuru, "loading:HandBack", 3L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(kasun, "loading:Start", 4L, "{\"tripId\":\"" + tripId + "\"}", 200);

    JsonNode item = manifest(kasun).get("lines").get(1).get("items").get(0);
    assertEquals("LOADED", item.get("status").asText());
    assertEquals(isuruId.toString(), item.get("checkedBy").asText(), "the tick keeps Isuru's name");
  }

  @Test
  void anAuditorCannotHandBackATrip() throws Exception {
    String email = "aud-" + UUID.randomUUID() + "@loading.test";
    accounts.createAccount(email, "Auditor", PASSWORD, "auditor");
    accounts.grantDepot(email, depot);
    Cookie auditor = session(email);
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);

    send(auditor, "loading:HandBack", 2L, "{\"tripId\":\"" + tripId + "\"}", 403);
    assertEquals(2, manifest(isuru).get("rowVersion").asLong());
  }

  @Test
  void itemCheckAttemptsCannotBeUpdatedOrDeleted() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200);
    UUID checkId = database.asSystem(ModuleRole.LOADING, () ->
        (UUID) database.queryOne(
            "SELECT check_id FROM loading.item_checks WHERE trip_id = ? LIMIT 1", tripId).get("check_id"));

    assertThrows(DataAccessException.class, () -> database.asSystem(ModuleRole.LOADING, () ->
        database.update("UPDATE loading.item_checks SET status = status WHERE check_id = ?", checkId)));
    assertThrows(DataAccessException.class, () -> database.asSystem(ModuleRole.LOADING, () ->
        database.update("DELETE FROM loading.item_checks WHERE check_id = ?", checkId)));
    assertEquals(1, database.asSystem(ModuleRole.LOADING, () -> database.query(
        "SELECT check_id FROM loading.item_checks WHERE check_id = ?", checkId).size()));
  }

  @Test
  void aFlaggedItemIsNotLoadedLoadingCarriesOnAndReleaseFollowsTheChecklist() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    String blocked = send(isuru, "loading:Release", 2L, release(true), 409);
    assertTrue(blocked.contains("R-LOD-07"), blocked);

    send(isuru, "loading:Shortfall", 2L,
        "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderB + "\",\"lineNo\":1,\"kind\":\"MISSING\","
            + "\"missingUnits\":4,\"reason\":\"Not on the pallet\",\"photoAttachmentId\":null}", 200);
    assertEquals(1, outbox("loading.shortfall"), "the dispatcher and store are told");
    assertEquals("BLOCKED", manifest(isuru).get("status").asText());

    send(isuru, "loading:Check", 3L,
        "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderA + "\",\"status\":\"LOADED\"}", 200);
    assertEquals("READY", manifest(isuru).get("status").asText());

    String noDoors = send(isuru, "loading:Release", 4L, release(false), 422);
    assertTrue(noDoors.contains("R-LOD-10"), noDoors);
    send(isuru, "loading:Release", 4L, release(true), 200);

    JsonNode done = manifest(isuru);
    assertEquals("COMPLETED", done.get("status").asText());
    assertTrue(done.get("holder").isNull() || done.get("holder").isMissingNode() || done.get("holder").isEmpty());
    assertEquals(1, outbox("trip.released"));
    String postReleaseVehicle = reference.availableVehicles(depot, serviceDate, null).stream()
        .filter(v -> !v.vehicleId().equals(vehicleId)).findFirst().orElseThrow().vehicleId();
    deliver("loading.on-plan-revised", new PlanRevised(planId, depot, serviceDate, 2, planId,
        "late allocation update", List.of(new PlannedTrip(tripId, postReleaseVehicle, 1, "Fresh", "Kandy",
            "ambient", LocalTime.of(4, 30), List.of(stop(1, orderA), stop(2, orderB))))));
    JsonNode afterRevision = manifest(isuru);
    assertEquals(1, afterRevision.get("planVersion").asInt(), "a released manifest is historical");
    assertEquals(5, afterRevision.get("rowVersion").asLong());
    assertEquals("COMPLETED", afterRevision.get("status").asText());
    JsonNode shortfalls = json(read(isuru, "/api/loading/shortfalls?depot=" + depot, 200));
    long thisTripsShortfalls = java.util.stream.StreamSupport.stream(shortfalls.spliterator(), false)
        .filter(s -> tripId.toString().equals(s.get("tripId").asText())).count();
    assertEquals(1, thisTripsShortfalls);
  }

  @Test
  void aRevisionCarriesUnchangedTicksAndResetsChangedOnes() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Check", 2L,
        "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderA + "\",\"status\":\"LOADED\"}", 200);
    send(isuru, "loading:Check", 3L, check(orderB, 1, "LOADED"), 200);

    UUID orderC = place("[{\"productId\":\"P-4\",\"quantity\":1}]");
    deliver("loading.on-plan-revised", new PlanRevised(planId, depot, serviceDate, 2, planId, "vehicle swap",
        List.of(trip(List.of(stop(1, orderA), stop(2, orderC))))));

    JsonNode m = manifest(isuru);
    assertEquals(2, m.get("planVersion").asInt());
    assertEquals(5, m.get("rowVersion").asLong(), "a revision moves the version on by one");
    assertEquals("PENDING", line(m, orderC).get("status").asText(), "R-LOD-03: new work is unchecked");
    assertEquals("LOADED", line(m, orderA).get("status").asText(), "unchanged ticks carry over");
    send(isuru, "loading:Check", 4L, check(orderA, 1, "LOADED"), 409);
  }

  @Test
  void aQueuedCheckReplayedThroughSyncAppliesOnce() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    String op = envelope(UUID.randomUUID(), "loading:Check", 2L, check(orderA, 1, "LOADED"));
    String batch = "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"operations\":[{\"sequence\":1,\"command\":" + op + "}]}";
    JsonNode first = json(sync(isuru, batch));
    JsonNode second = json(sync(isuru, batch));
    assertEquals("APPLIED", first.get("results").get(0).get("status").asText());
    assertTrue(second.get("results").get(0).get("replayed").asBoolean());
    assertEquals(3, manifest(isuru).get("rowVersion").asLong());
  }

  @Test
  void queuedLoadingWorkKeepsTheOperatorWhoRecordedItAfterAUserSwitch() throws Exception {
    String op = envelope(UUID.randomUUID(), "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}");
    clock.set(clock.now().plusMillis(1));
    switchOperator(isuru, kasunId, "2468");
    String batch = "{\"deviceId\":\"" + UUID.randomUUID()
        + "\",\"operations\":[{\"sequence\":1,\"command\":" + op + "}]}";
    JsonNode result = json(sync(isuru, batch)).get("results").get(0);
    assertEquals("APPLIED", result.get("status").asText());
    assertEquals(isuruId.toString(), manifest(isuru).get("holder").get("userId").asText());
  }

  @Test
  void queuedLoadingWorkCannotClaimAnOperatorWhoWasNotActiveAtItsRecordedTime() throws Exception {
    String op = envelope(UUID.randomUUID(), "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", kasunId);
    clock.set(clock.now().plusMillis(1));
    switchOperator(isuru, kasunId, "2468");
    String batch = "{\"deviceId\":\"" + UUID.randomUUID()
        + "\",\"operations\":[{\"sequence\":1,\"command\":" + op + "}]}";
    JsonNode result = json(sync(isuru, batch)).get("results").get(0);
    assertEquals("REJECTED", result.get("status").asText());
    assertEquals(1, manifest(isuru).get("rowVersion").asLong());
  }

  @Test
  void fiveWrongOperatorPinsStartAFiveMinuteRetryAfterPause() throws Exception {
    MvcResult locked = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .delete("/api/session/operator").cookie(isuru)).andReturn();
    assertEquals(204, locked.getResponse().getStatus());
    for (int failure = 1; failure <= 5; failure++) {
      MvcResult result = http.perform(post("/api/session/operator")
          .cookie(isuru)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"userId\":\"" + isuruId + "\",\"pin\":\"9999\"}"))
          .andReturn();
      assertEquals(401, result.getResponse().getStatus());
      assertEquals(5 - failure, mapper.readTree(result.getResponse().getContentAsString()).get("triesLeft").asInt());
    }
    MvcResult paused = http.perform(post("/api/session/operator")
        .cookie(isuru)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + isuruId + "\",\"pin\":\"1357\"}"))
        .andReturn();
    assertEquals(429, paused.getResponse().getStatus());
    assertEquals("300", paused.getResponse().getHeader("Retry-After"));
  }

  // ---- helpers -------------------------------------------------------------

  @Test
  void simultaneousChecksCannotBothConsumeTheSameVersion() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      java.util.concurrent.Callable<Integer> tick = () -> {
        ready.countDown();
        assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
        return http.perform(post("/api/commands").cookie(isuru).contentType(MediaType.APPLICATION_JSON)
            .content(envelope(UUID.randomUUID(), "loading:Check", 2L, check(orderA, 1, "LOADED"))))
            .andReturn().getResponse().getStatus();
      };
      var first = workers.submit(tick);
      var second = workers.submit(tick);
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      var statuses = List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
          second.get(30, java.util.concurrent.TimeUnit.SECONDS)).stream().sorted().toList();
      assertEquals(List.of(200, 409), statuses);
      assertEquals(3, manifest(isuru).get("rowVersion").asLong());
      assertEquals(1, line(manifest(isuru), orderA).get("items").get(0).get("attempt").asInt());
    } finally {
      start.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void replayingAPublishedVersionDoesNotResetWorkOrAdvanceTheVersion() throws Exception {
    send(isuru, "loading:Start", 1L, "{\"tripId\":\"" + tripId + "\"}", 200);
    send(isuru, "loading:Check", 2L, check(orderA, 1, "LOADED"), 200);
    deliver("loading.on-plan-published", new PlanPublished(planId, depot, serviceDate, 1, Optional.empty(),
        List.of(trip(List.of(stop(1, orderA), stop(2, orderB))))));
    JsonNode m = manifest(isuru);
    assertEquals(3, m.get("rowVersion").asLong());
    assertEquals("LOADED", line(m, orderA).get("items").get(0).get("status").asText());
  }

  @Test
  void rowLevelSecurityHidesTripAndChildrenFromAnActorWithoutDepotScope() {
    UUID noScope = accounts.createAccount("rls-" + UUID.randomUUID() + "@loading.test",
        "No scope", PASSWORD, "loader");
    database.asModule(ModuleRole.LOADING, noScope, () -> {
      for (String table : List.of("trips", "stops", "items", "sessions", "item_checks", "shortfalls")) {
        assertTrue(database.query("SELECT 1 FROM loading." + table + " WHERE trip_id = ?", tripId).isEmpty(), table);
      }
    });
  }

  private PlannedTrip trip(List<PlannedStop> stops) {
    return new PlannedTrip(tripId, vehicleId, 1, "Fresh", "Kandy", "ambient", LocalTime.of(4, 30), stops);
  }

  private static PlannedStop stop(int sequence, UUID orderId) {
    return new PlannedStop(sequence, orderId, OUTLET, LocalTime.of(5, 0).plusMinutes(sequence * 20L));
  }

  private UUID place(String lines) throws Exception {
    String body = envelope(UUID.randomUUID(), "order:Place", null,
        "{\"outletId\":\"" + OUTLET + "\",\"requestedDate\":\"" + serviceDate + "\",\"lines\":" + lines + "}");
    return UUID.fromString(json(command(manager, body, 200)).get("result").get("orderId").asText());
  }

  private String check(UUID orderId, int lineNo, String status) {
    return "{\"tripId\":\"" + tripId + "\",\"orderId\":\"" + orderId + "\",\"lineNo\":" + lineNo
        + ",\"status\":\"" + status + "\"}";
  }

  private String release(boolean doorsSealed) {
    return "{\"tripId\":\"" + tripId + "\",\"doorsSealed\":" + doorsSealed
        + ",\"ordersSecured\":true,\"driverPresent\":true}";
  }

  private JsonNode manifest(Cookie who) throws Exception {
    return json(read(who, "/api/loading/trips/" + tripId + "/manifest", 200));
  }

  private static JsonNode line(JsonNode manifest, UUID orderId) {
    for (JsonNode l : manifest.get("lines")) {
      if (l.get("orderId").asText().equals(orderId.toString())) {
        return l;
      }
    }
    throw new AssertionError("no line for " + orderId);
  }

  private static JsonNode find(JsonNode board, UUID id) {
    for (JsonNode t : board) {
      if (t.get("tripId").asText().equals(id.toString())) {
        return t;
      }
    }
    throw new AssertionError("trip not on the board");
  }

  private long result(String response) throws Exception {
    return json(response).get("result").get("rowVersion").asLong();
  }

  private String send(Cookie who, String kind, Long version, String payload, int expected) throws Exception {
    return command(who, envelope(UUID.randomUUID(), kind, version, payload, operatorId(who)), expected);
  }

  private String envelope(UUID id, String kind, Long version, String payload) {
    return envelope(id, kind, version, payload, isuruId);
  }

  private String envelope(UUID id, String kind, Long version, String payload, UUID operatorId) {
    String actingUser = kind.startsWith("loading:")
        ? ",\"actingUserId\":\"" + operatorId + "\""
        : "";
    return "{\"commandId\":\"" + id + "\",\"kind\":\"" + kind + "\",\"expectedVersion\":" + version
        + ",\"payload\":" + payload + ",\"clientRecordedAt\":\"" + clock.now() + "\"" + actingUser + "}";
  }

  private UUID operatorId(Cookie who) {
    if (who.getValue().equals(kasun.getValue())) return kasunId;
    if (who.getValue().equals(outsider.getValue())) return outsiderId;
    return isuruId;
  }

  private String command(Cookie who, String body, int expected) throws Exception {
    MvcResult r = http.perform(post("/api/commands").cookie(who)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String sync(Cookie who, String body) throws Exception {
    MvcResult r = http.perform(post("/api/sync").cookie(who)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String read(Cookie who, String path, int expected) throws Exception {
    MvcResult r = http.perform(get(path).cookie(who)).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private JsonNode json(String body) throws Exception {
    return mapper.readTree(body);
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private void switchOperator(Cookie who, UUID userId, String pin) throws Exception {
    MvcResult result = http.perform(post("/api/session/operator")
        .cookie(who)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + userId + "\",\"pin\":\"" + pin + "\"}"))
        .andReturn();
    assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
  }

  private int outbox(String type) {
    List<Map<String, Object>> rows = database.asModule(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT event_id FROM integration.outbox_events WHERE aggregate_id = ? AND event_type = ?",
        tripId.toString(), type));
    return rows.size();
  }

  private boolean audited(String action, String decision) {
    return !database.asModule(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT 1 FROM integration.audit_log WHERE action = ? AND decision = ? AND resource LIKE ?",
        action, decision, "wpt:loading:depot:" + depot)).isEmpty();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void deliver(String consumer, DomainEvent payload) {
    UUID eventId = UUID.randomUUID();
    EventSubscriber subscriber =
        subscribers.stream().filter(s -> s.consumerName().equals(consumer)).findFirst().orElseThrow();
    EventEnvelope envelope = new EventEnvelope(eventId, payload.type(), 1, Instant.now(), "test",
        Optional.empty(), Optional.empty(), payload);
    database.asSystem(subscriber.moduleRole(), () -> {
      if (inbox.claim(consumer, eventId)) {
        subscriber.on(envelope);
      }
    });
    assertFalse(subscribers.isEmpty());
  }
}
