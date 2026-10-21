package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.execution.ExecutionTestConfig.MovableClock;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingStarted;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.messaging.OutboxRelay;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliverySkipped;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDatabase;
import com.waypoint.dispatch.support.TestDates;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * R-EXE-31 end to end: the dispatcher skips a store or ends a trip on the road,
 * through the command bus, the outbox and the relay. The same order goes to the
 * next operating day, or is cancelled with its goods back at the depot and no
 * warehouse call; only the depot's dispatcher may; a stale version and a
 * redelivered skip change nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class StopSkipIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String NEXT_OUTLET = "OUT002";
  static final String MALL_OUTLET = "OUT015";
  static final String OTHER_DEPOT_OUTLET = "OUT076";
  static final String PASSWORD = "OnTheRoad2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired MovableClock clock;
  @Autowired EventPublisher publisher;
  @Autowired OutboxRelay relay;

  String depot;
  LocalDate day;
  String vehicleId;
  String orderer;
  String storeOne;
  String storeTwo;
  String driver;
  String dispatcher;
  String farDispatcher;
  UUID storeOneId;
  UUID storeTwoId;
  UUID dispatcherId;
  UUID driverId;
  UUID tripId;
  UUID stopOne;
  UUID stopTwo;
  UUID mallStop;
  UUID orderOne;
  UUID orderTwo;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void setUp() throws Exception {
    clock.reset();
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    database.asSystemSeparately(
        ModuleRole.INTEGRATION,
        () -> database.update(
            "UPDATE integration.outbox_events SET status = 'published'"
                + " WHERE status IN ('pending','failed','processing')"));

    depot = reference.outlet(OUTLET, null).orElseThrow().depotCode();
    do {
      day = TestDates.unusedDay(reference::nextOperatingDay);
      vehicleId = reference.availableVehicles(depot, day, null).get(0).vehicleId();
    } while (alreadyAssigned(vehicleId, day));
    clock.set(day.minusDays(1).atTime(LocalTime.of(10, 0)).atZone(Clock.OPERATING_ZONE).toInstant());

    String run = UUID.randomUUID().toString().substring(0, 8);
    orderer = "do-" + run + "@skips.test";
    storeOne = "d1-" + run + "@skips.test";
    storeTwo = "d2-" + run + "@skips.test";
    driver = "dd-" + run + "@skips.test";
    dispatcher = "dp-" + run + "@skips.test";
    farDispatcher = "df-" + run + "@skips.test";
    UUID ordererId = accounts.createAccount(orderer, "Orders", PASSWORD, "store_manager");
    storeOneId = accounts.createAccount(storeOne, "Store One", PASSWORD, "store_manager");
    storeTwoId = accounts.createAccount(storeTwo, "Store Two", PASSWORD, "store_manager");
    driverId = accounts.createAccount(driver, "Driver", PASSWORD, "driver");
    dispatcherId = accounts.createAccount(dispatcher, "Dispatcher", PASSWORD, "dispatcher");
    accounts.createAccount(farDispatcher, "Far Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(dispatcher, depot);
    accounts.grantDepot(farDispatcher, reference.outlet(OTHER_DEPOT_OUTLET, null).orElseThrow().depotCode());
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          for (String outlet : List.of(OUTLET, NEXT_OUTLET, MALL_OUTLET)) {
            grantOutlet(ordererId, outlet);
          }
          grantOutlet(storeOneId, OUTLET);
          grantOutlet(storeTwoId, NEXT_OUTLET);
          database.update(
              "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
                  + " VALUES (?, ?, daterange(?::date, ?::date))",
              vehicleId, driverId, Date.valueOf(day), Date.valueOf(day.plusDays(1)));
        });

    orderOne = place(OUTLET, "[{\"productId\":\"P-1\",\"quantity\":24}]");
    orderTwo = place(NEXT_OUTLET, "[{\"productId\":\"P-3\",\"quantity\":10}]");
    UUID mallOrder = place(MALL_OUTLET, "[{\"productId\":\"P-4\",\"quantity\":6}]");

    at("04:40");
    UUID planId = UUID.randomUUID();
    tripId = UUID.randomUUID();
    publish(
        ModuleRole.PLANNING,
        new PlanPublished(planId, depot, day, 1, Optional.empty(), List.of(new PlannedTrip(
            tripId, vehicleId, 1, "Fresh", "Colombo", "ambient", LocalTime.of(4, 30),
            List.of(
                new PlannedStop(1, orderOne, OUTLET, LocalTime.of(5, 20)),
                new PlannedStop(2, orderTwo, NEXT_OUTLET, LocalTime.of(5, 40)),
                new PlannedStop(3, mallOrder, MALL_OUTLET, LocalTime.of(9, 30)))))));
    publish(ModuleRole.LOADING, new LoadingStarted(tripId, planId, vehicleId));
    publish(
        ModuleRole.LOADING,
        new TripReleased(
            tripId, planId, 1, vehicleId, depot, day,
            List.of(
                new ReleasedStop(1, orderOne, OUTLET, LocalTime.of(5, 20)),
                new ReleasedStop(2, orderTwo, NEXT_OUTLET, LocalTime.of(5, 40)),
                new ReleasedStop(3, mallOrder, MALL_OUTLET, LocalTime.of(9, 30)))));
    drain();

    JsonNode stops = json(read(driver, "/api/execution/run-sheets?date=" + day, 200)).get(0).get("stops");
    stopOne = UUID.fromString(stops.get(0).get("deliveryId").asText());
    stopTwo = UUID.fromString(stops.get(1).get("deliveryId").asText());
    mallStop = UUID.fromString(stops.get(2).get("deliveryId").asText());
    at("07:10");
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aReachedStoreIsSkippedToTheNextDayAndTheDriversNextWriteIsRefused() throws Exception {
    JsonNode arrived = json(command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200)).get("result");
    long version = arrived.get("rowVersion").asLong();

    JsonNode skipped = json(skip(dispatcher, stopOne, version, "NEXT_DAY", "Store closed, nobody answers", 200)).get("result");
    assertEquals("SKIPPED", skipped.get("outcome").asText());
    drain();

    LocalDate next = reference.nextOperatingDay(day.plusDays(1));
    JsonNode order = json(read(orderer, "/api/orders/" + orderOne, 200));
    assertEquals("DEFERRED", order.get("status").asText(), "the same order, not a new one");
    assertEquals(next.toString(), order.get("deliveryDate").asText(), "onto the next operating day's plan");

    // R-NOT-20: the store and the driver are told once; the dispatcher who decided is not.
    assertEquals(1, notified(storeOneId, "delivery.skipped"));
    assertEquals(1, notified(driverId, "delivery.skipped"));
    assertEquals(0, notified(dispatcherId, "delivery.skipped"));
    assertTrue(threadSays(storeOne,
        "Today's delivery moves to the next delivery day and goes first. Reason: Store closed, nobody answers"),
        "the store reads why on the trip's thread");

    // The dispatcher sees the driver has not read the push yet, then that they have.
    JsonNode skip = skippedStop(stopOne).get("skip");
    assertEquals("NEXT_DAY", skip.get("then").asText());
    assertEquals("Store closed, nobody answers", skip.get("reason").asText());
    assertEquals(false, skip.get("seenByDriver").asBoolean(), "not seen by the driver yet");
    String push = json(read(driver, "/api/notifications", 200)).get("items").get(0).get("notificationId").asText();
    command(driver, "notification:MarkRead", null, "{\"notificationIds\":[\"" + push + "\"]}", 200);
    assertTrue(skippedStop(stopOne).get("skip").get("seenByDriver").asBoolean(), "seen once the driver read it");

    // The phone that had not heard yet: its delivery is refused, never applied over the skip.
    String refused = command(driver, "delivery:Record", version + 1,
        "{\"deliveryId\":\"" + stopOne + "\",\"outcome\":\"DELIVERED\"}", 409);
    assertTrue(refused.contains("R-EXE-01"), refused);
  }

  @Test
  void aClosedStoreIsReportedForTheDispatcherToDecideAndTheDriverCannotFailIt() throws Exception {
    // R-EXE-32: the stop stays open; the dispatcher and the store hear; only Skip store ends it.
    long version = json(command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200)).get("result").get("rowVersion").asLong();
    String report = "{\"deliveryId\":\"" + stopOne + "\",\"problem\":\"STORE_CLOSED\"}";
    command(dispatcher, "delivery:ReportStopProblem", null, report, 403);
    JsonNode reported = json(command(driver, "delivery:ReportStopProblem", null, report, 200)).get("result");
    assertFalse(reported.get("stopEnded").asBoolean());
    assertEquals("STORE_CLOSED", skippedStop(stopOne).get("reportedProblem").asText(), "the run sheet says it waits");
    assertEquals("ARRIVED", skippedStop(stopOne).get("outcome").asText(), "the stop itself is not touched");
    drain();

    assertEquals(1, notified(dispatcherId, "delivery.problem_reported"), "the dispatcher decides");
    assertEquals(1, notified(storeOneId, "delivery.problem_reported"));
    assertTrue(threadSays(dispatcher, "Store closed, nobody to receive"), "a report on the trip's thread");
    assertTrue(threadSays(storeOne,
        "The driver is at your store and found it closed. The dispatcher decides what happens to today's delivery and will tell you here."));

    // The driver cannot end it with "Not delivered", by any reason.
    String closed = command(driver, "delivery:Record", version,
        "{\"deliveryId\":\"" + stopOne + "\",\"outcome\":\"FAILED\",\"reason\":\"refused\",\"dispositionNote\":\"back to depot\"}", 409);
    assertTrue(closed.contains("R-EXE-32"), closed);

    // The dispatcher skips it; a report still queued on a phone with no signal lands quietly.
    skip(dispatcher, stopOne, version, "NEXT_DAY", "Store closed, the driver reported it", 200);
    JsonNode late = json(command(driver, "delivery:ReportStopProblem", null, report, 200)).get("result");
    assertTrue(late.get("stopEnded").asBoolean(), "recorded, no conflict for the driver");
    drain();
    assertEquals(1, notified(dispatcherId, "delivery.problem_reported"), "nobody is told twice");
  }

  @Test
  void aBreakdownIsSaidPlainlyInTheRoomAndPushedOnlyToStoresStillWaiting() throws Exception {
    // R-MSG-17, R-NOT-22: the first store is done (skipped), the second still waits.
    skip(dispatcher, stopOne, 1L, "NEXT_DAY", "Store closed all day", 200);
    command(driver, "delivery:ReportFault", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"kind\":\"vehicle\",\"description\":\"clutch gone, smoke\"}", 200);
    drain();
    assertEquals(1, notified(storeTwoId, "vehicle.fault_reported"), "the store still waiting is pushed");
    assertEquals(0, notified(storeOneId, "vehicle.fault_reported"), "the store already done is not");
    String notice = vehicleId + " is delayed: breakdown";
    assertTrue(threadSays(storeTwo, notice));
    assertTrue(threadSays(storeOne, notice), "a store already done still reads it in the room");
    assertFalse(threadSays(storeTwo, "clutch gone, smoke"), "never the driver's own words");
  }

  @Test
  void theDriverWatchesTheirOwnVehicleForChangesAndNobodyElseCan() throws Exception {
    // Live refresh phase 3: a skip reaches the driver's phone as a signal, not only by push and poll.
    MvcResult own = http.perform(get("/api/live/stream?parts=changes&changes.vehicle=" + vehicleId + "&changes.date=" + day)
        .cookie(session(driver))).andReturn();
    assertEquals(200, own.getResponse().getStatus(), own.getResponse().getContentAsString());
    assertTrue(own.getRequest().isAsyncStarted());
    read(storeOne, "/api/changes/stream?vehicle=" + vehicleId + "&date=" + day, 403);
    read(dispatcher, "/api/changes/stream?vehicle=" + vehicleId + "&date=" + day, 403);
    read(driver, "/api/changes/stream?vehicle=" + vehicleId + "&date=" + day.plusDays(30), 403);
  }

  @Test
  void aDriverRecordsNoClosedStoreOrBlockedStopAsNotDelivered() throws Exception {
    // R-EXE-32: these are reported, not failed, even before any report.
    for (String reason : List.of("outlet_closed", "access_blocked")) {
      String refused = command(driver, "delivery:Record", 1L,
          "{\"deliveryId\":\"" + stopTwo + "\",\"outcome\":\"FAILED\",\"reason\":\"" + reason + "\",\"dispositionNote\":\"back to depot\"}", 409);
      assertTrue(refused.contains("R-EXE-32"), refused);
    }
  }

  @Test
  void aCancelledStopReturnsItsOrderAndTheWarehouseIsNotCalled() throws Exception {
    skip(dispatcher, stopTwo, 1L, "CANCEL", "Store refused the delivery", 200);
    drain();
    assertEquals("CANCELLED", json(read(orderer, "/api/orders/" + orderTwo, 200)).get("status").asText());
    long asked = ((Number) database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> database.queryOne(
        "SELECT count(*) AS n FROM warehouse.status_requests WHERE order_id = ? AND target_status = 'cancelled'",
        orderTwo)).get("n")).longValue();
    assertEquals(0, asked, "the goods came back to the depot; staff put the stock right by hand");
  }

  @Test
  void onlyTheDepotsDispatcherSkipsAndAStaleVersionIsAConflict() throws Exception {
    skip(farDispatcher, stopOne, 1L, "NEXT_DAY", "Not my depot", 403);
    skip(driver, stopOne, 1L, "NEXT_DAY", "Drivers cannot skip", 403);
    skip(storeOne, stopOne, 1L, "NEXT_DAY", "Nor can a store", 403);
    skip(dispatcher, stopOne, 1L, "NEXT_DAY", "no", 422);

    // The driver arrived first: the dispatcher's skip on the old version is refused, not merged.
    command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200);
    skip(dispatcher, stopOne, 1L, "NEXT_DAY", "Store closed, nobody answers", 409);
  }

  @Test
  void endingATripSkipsEveryStopNotYetDoneAndLeavesADeliveredOne() throws Exception {
    command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200);
    command(driver, "delivery:Record", 2L,
        "{\"deliveryId\":\"" + stopOne + "\",\"outcome\":\"DELIVERED\"}", 200);

    command(farDispatcher, "delivery:EndTrip", null, endTrip("Not my depot"), 403);
    command(driver, "delivery:EndTrip", null, endTrip("Drivers cannot end a trip"), 403);
    JsonNode ended = json(command(dispatcher, "delivery:EndTrip", null, endTrip("Vehicle broke down"), 200)).get("result");
    assertEquals(2, ended.get("skipped").asInt(), "the two stops not yet done");
    command(dispatcher, "delivery:EndTrip", null, endTrip("Again"), 409);
    drain();

    assertEquals("DEFERRED", json(read(orderer, "/api/orders/" + orderTwo, 200)).get("status").asText());
    assertNotEquals("DEFERRED", json(read(orderer, "/api/orders/" + orderOne, 200)).get("status").asText(),
        "a delivered stop stands");
  }

  @Test
  void aRedeliveredSkipDoesNotMoveTheOrderAgain() throws Exception {
    skip(dispatcher, stopTwo, 1L, "NEXT_DAY", "Store closed early", 200);
    drain();
    String once = json(read(orderer, "/api/orders/" + orderTwo, 200)).get("deliveryDate").asText();

    // At least once delivery: the same skip again, after the order already moved on.
    publish(ModuleRole.EXECUTION, new DeliverySkipped(
        stopTwo, orderTwo, tripId, NEXT_OUTLET, depot, vehicleId, day, "NEXT_DAY", "Store closed early", false,
        clock.now()));
    drain();
    JsonNode order = json(read(orderer, "/api/orders/" + orderTwo, 200));
    assertEquals(once, order.get("deliveryDate").asText(), "deferred once, not twice");
    assertEquals(1, order.get("deferralCount").asInt());
  }

  @Test
  void theChatSuggestsDecisionsForTheStopAndKeepsWhichWasTaken() throws Exception {
    // Issue #269: the store is closed, the van is at the door, the window closes in 20 minutes.
    command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200);
    JsonNode s = json(read(dispatcher, "/api/ml/decision-suggestions?deliveryId=" + stopOne + "&situation=failed_delivery", 200));
    assertEquals("STORE_CLOSED", s.get("situation").asText(), "a report type names its situation");
    assertEquals("rules", s.get("basis").asText());
    assertEquals("CALL_STORE", s.get("suggestions").get(0).get("action").asText());
    assertEquals(3, s.get("suggestions").size(), "at most three chips");
    assertTrue(s.get("playbook").get("steps").size() > 0);
    read(farDispatcher, "/api/ml/decision-suggestions?deliveryId=" + stopOne + "&situation=failed_delivery", 403);
    assertEquals(11, json(read(dispatcher, "/api/ml/playbooks", 200)).size());

    String shown = "\"shown\":[\"CALL_STORE\",\"SKIP_NEXT_DAY\",\"WAIT\"]";
    command(dispatcher, "ml:RecordSuggestion", null,
        "{\"deliveryId\":\"" + stopOne + "\",\"situation\":\"STORE_CLOSED\"," + shown + ",\"chosen\":\"SKIP_NEXT_DAY\"}", 200);
    command(dispatcher, "ml:RecordSuggestion", null,
        "{\"deliveryId\":\"" + stopOne + "\",\"situation\":\"STORE_CLOSED\"," + shown + ",\"chosen\":\"END_TRIP\"}", 422);
    command(driver, "ml:RecordSuggestion", null,
        "{\"deliveryId\":\"" + stopOne + "\",\"situation\":\"STORE_CLOSED\"," + shown + "}", 403);
    long kept = ((Number) database.asSystemSeparately(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.decision_suggestions WHERE delivery_id = ? AND chosen = 'SKIP_NEXT_DAY'",
        stopOne)).get("n")).longValue();
    assertEquals(1, kept);
  }

  @Test
  void aDecisionTakenWithoutAChipIsLoggedAgainstTheChipsThatWereShown() throws Exception {
    // The chips beside a report were on screen; the dispatcher skipped with the Skip button instead.
    long version = json(command(driver, "delivery:RecordArrival", 1L, stop(stopOne), 200)).get("result").get("rowVersion").asLong();
    String shown = "\"shown\":[\"CALL_STORE\",\"SKIP_NEXT_DAY\",\"WAIT\"]";
    command(dispatcher, "ml:RecordSuggestion", null,
        "{\"deliveryId\":\"" + stopOne + "\",\"situation\":\"STORE_CLOSED\"," + shown + ",\"shownOnly\":true}", 200);
    skip(dispatcher, stopOne, version, "CANCEL", "Store closed for the season", 200);
    drain();

    java.util.Map<String, Object> row = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT chosen, taken, via_chip FROM ml.decision_suggestions WHERE delivery_id = ? ORDER BY decided_at DESC LIMIT 1",
        stopOne));
    assertEquals("SKIP_CANCEL", row.get("taken"), "what was done");
    assertEquals(null, row.get("chosen"), "not among the chips shown");
    assertEquals(false, row.get("via_chip"));
  }

  @Test
  void afterTwentyDecisionsTheChancesAreCountedFromWhatDispatchersTook() throws Exception {
    command(driver, "delivery:RecordArrival", 1L, stop(stopTwo), 200);
    // A situation of its own, so the rule-prior test above stays on rules whatever the order.
    String shown = "\"shown\":[\"TELL_STORE\",\"WAIT\",\"END_TRIP\"]";
    for (int i = 0; i < 21; i++) {
      command(dispatcher, "ml:RecordSuggestion", null,
          "{\"deliveryId\":\"" + stopTwo + "\",\"situation\":\"ROAD_BLOCKED\"," + shown + ",\"chosen\":\"END_TRIP\"}", 200);
    }
    JsonNode s = json(read(dispatcher, "/api/ml/decision-suggestions?deliveryId=" + stopTwo + "&situation=road_disruption", 200));
    assertEquals("learned", s.get("basis").asText());
    JsonNode first = s.get("suggestions").get(0);
    assertEquals("END_TRIP", first.get("action").asText(), "what dispatchers take here comes first, over the rules' order");
    assertTrue(first.get("chosenTimes").asInt() >= 21);
    assertTrue(first.get("shownTimes").asInt() >= first.get("chosenTimes").asInt());
  }

  // ---- helpers ----

  private String skip(String who, UUID deliveryId, Long version, String then, String reason, int expected) throws Exception {
    return command(who, "delivery:SkipStop", version,
        "{\"deliveryId\":\"" + deliveryId + "\",\"then\":\"" + then + "\",\"reason\":\"" + reason + "\"}", expected);
  }

  private String endTrip(String reason) {
    return "{\"tripId\":\"" + tripId + "\",\"reason\":\"" + reason + "\"}";
  }

  private JsonNode skippedStop(UUID deliveryId) throws Exception {
    for (JsonNode sheet : json(read(dispatcher, "/api/execution/run-sheets?depot=" + depot + "&date=" + day, 200))) {
      for (JsonNode stop : sheet.get("stops")) {
        if (stop.get("deliveryId").asText().equals(deliveryId.toString())) return stop;
      }
    }
    throw new AssertionError("no stop " + deliveryId);
  }

  private boolean threadSays(String who, String body) throws Exception {
    String thread = json(read(who, "/api/threads/by-subject?type=trip&id=" + tripId, 200)).get("threadId").asText();
    for (JsonNode m : json(read(who, "/api/threads/" + thread + "/messages", 200)).get("items")) {
      if (body.equals(m.get("body").asText())) return true;
    }
    return false;
  }

  private static String stop(UUID deliveryId) {
    return "{\"deliveryId\":\"" + deliveryId + "\"}";
  }

  private long notified(UUID userId, String eventType) {
    return ((Number) database.asSystemSeparately(ModuleRole.NOTIFICATION, () -> database.queryOne(
        "SELECT count(*) AS n FROM notification.notifications WHERE recipient_user_id = ? AND event_type = ?",
        userId, eventType)).get("n")).longValue();
  }

  private void grantOutlet(UUID userId, String outlet) {
    database.update("INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)", userId, outlet);
  }

  private boolean alreadyAssigned(String vehicle, LocalDate on) {
    return database.asSystemSeparately(
        ModuleRole.IAM,
        () -> database.queryOne(
                "SELECT 1 FROM iam.vehicle_driver_assignments WHERE vehicle_id = ?"
                    + " AND validity && daterange(?::date, ?::date) LIMIT 1",
                vehicle, Date.valueOf(on.minusDays(1)), Date.valueOf(on.plusDays(2)))
            != null);
  }

  private void at(String time) {
    clock.set(day.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant());
  }

  private String instant(String time) {
    return day.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant().toString();
  }

  private void publish(ModuleRole role, DomainEvent event) {
    database.asSystem(role, () -> publisher.publish(Actor.SYSTEM, event));
  }

  private void drain() {
    for (int pass = 0; pass < 100; pass++) {
      if (relay.deliverBatch() == 0) {
        Object failures = database.asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.query(
                "SELECT event_type, last_error FROM integration.outbox_events"
                    + " WHERE status IN ('failed','dead') AND occurred_at >= ?",
                java.sql.Timestamp.from(clock.now().minusSeconds(86_400 * 2))));
        assertEquals("[]", String.valueOf(failures), "an event of this test failed delivery");
        return;
      }
    }
    throw new AssertionError("the relay never ran out of work");
  }

  private UUID place(String outlet, String lines) throws Exception {
    return UUID.fromString(json(command(orderer, "order:Place", null,
        "{\"outletId\":\"" + outlet + "\",\"requestedDate\":\"" + day + "\",\"lines\":" + lines + "}", 200))
        .get("result").get("orderId").asText());
  }

  private String command(String who, String kind, Long version, String payload, int expected) throws Exception {
    String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"expectedVersion\":" + version
        + ",\"payload\":" + payload + ",\"clientRecordedAt\":\"" + clock.now() + "\"}";
    MvcResult r =
        http.perform(post("/api/commands").cookie(session(who)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String read(String who, String path, int expected) throws Exception {
    MvcResult r = http.perform(get(path).cookie(session(who))).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private JsonNode json(String body) throws Exception {
    return mapper.readTree(body);
  }

}
