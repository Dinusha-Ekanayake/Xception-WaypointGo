package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.execution.ExecutionTestConfig.MovableClock;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.issues.application.DecisionExpiryJob;
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
 * Live decisions end to end (roadmap FF-09, R-ISS-12 to R-ISS-14) on a released
 * trip: the dispatcher asks one store one question, the store answers with a
 * tap, the dispatcher and the driver are told, and no answer by the deadline
 * becomes a reason to phone. Events go through the real outbox and relay.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class LiveDecisionIntegrationTest {
  /** Window 05:00 to 07:30, Peliyagoda. */
  static final String OUTLET = "OUT001";
  /** The next store on the trip, same depot. */
  static final String NEXT_OUTLET = "OUT002";
  /** A mall outlet of the same depot. */
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
  @Autowired DecisionExpiryJob expiry;

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
  UUID dispatcherId;
  UUID driverId;
  UUID tripId;
  UUID stopOne;
  UUID stopTwo;
  UUID mallStop;

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
    orderer = "do-" + run + "@decisions.test";
    storeOne = "d1-" + run + "@decisions.test";
    storeTwo = "d2-" + run + "@decisions.test";
    driver = "dd-" + run + "@decisions.test";
    dispatcher = "dp-" + run + "@decisions.test";
    farDispatcher = "df-" + run + "@decisions.test";
    UUID ordererId = accounts.createAccount(orderer, "Orders", PASSWORD, "store_manager");
    storeOneId = accounts.createAccount(storeOne, "Store One", PASSWORD, "store_manager");
    UUID storeTwoId = accounts.createAccount(storeTwo, "Store Two", PASSWORD, "store_manager");
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

    UUID orderOne = place(OUTLET, "[{\"productId\":\"P-1\",\"quantity\":24}]");
    UUID orderTwo = place(NEXT_OUTLET, "[{\"productId\":\"P-3\",\"quantity\":10}]");
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
  void theStoreSaysItCanReceiveLateAndTheDispatcherAndTheDriverAreTold() throws Exception {
    JsonNode asked = json(ask(dispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne
        + "\",\"receiveAt\":\"" + instant("08:20") + "\"}", 200)).get("result");
    UUID requestId = UUID.fromString(asked.get("requestId").asText());
    assertEquals("Can someone receive the delivery at about 08:20?", asked.get("question").asText(),
        "the store and the time are filled by the system");
    drain();
    assertEquals(1, notified(storeOneId, "decision.requested"));

    // Only the store asked sees the question; another store, the driver and another depot do not.
    assertEquals(1, decisions(storeOne).size());
    assertEquals(0, decisions(storeTwo).size());
    assertEquals(0, json(read(farDispatcher, "/api/issues/decisions?date=" + day, 200)).size());
    answer(storeTwo, requestId, 1, "{\"answer\":\"yes\"}", 403);

    JsonNode answered = json(answer(storeOne, requestId, 1, "{\"answer\":\"yes\",\"reason\":\"Night staff stay on\"}", 200));
    assertFalse(answered.get("result").get("late").asBoolean());
    answer(storeOne, requestId, 2, "{\"answer\":\"no\"}", 409);
    drain();

    JsonNode seen = decisions(dispatcher).get(0);
    assertEquals("answered", seen.get("state").asText());
    assertEquals("yes", seen.get("answer").asText());
    assertEquals("app", seen.get("answeredVia").asText());
    assertEquals("Night staff stay on", seen.get("reason").asText());
    assertEquals(1, notified(dispatcherId, "decision.answered"));
    assertEquals(1, notified(driverId, "decision.answered"), "a Yes changes the driver's stop");
    assertTrue(driverThreadSays(OUTLET + " will receive at about 08:20."), "the driver reads it on the trip thread");
  }

  @Test
  void aMallIsNeverAskedAndOnlyTheDepotsDispatcherAsks() throws Exception {
    ask(dispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + mallStop + "\",\"receiveAt\":\""
        + instant("11:30") + "\"}", 422);
    ask(farDispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne + "\",\"receiveAt\":\""
        + instant("08:20") + "\"}", 403);
    ask(storeOne, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne + "\",\"receiveAt\":\""
        + instant("08:20") + "\"}", 403);
    ask(dispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne + "\",\"receiveAt\":\""
        + instant("06:00") + "\"}", 422);
  }

  @Test
  void theNextStoreIsAskedToLetTheCrewReachItsGoods() throws Exception {
    JsonNode asked = json(ask(dispatcher, "{\"kind\":\"dock_help\",\"deliveryId\":\"" + stopTwo
        + "\",\"aboutDeliveryId\":\"" + stopOne + "\"}", 200)).get("result");
    assertEquals(
        "Goods for " + OUTLET + " (24 units) are in front of yours. Can the crew unload them at your dock to reach"
            + " your goods, and reload them?",
        asked.get("question").asText());
    // A store is never asked to help with goods behind its own.
    ask(dispatcher, "{\"kind\":\"dock_help\",\"deliveryId\":\"" + stopOne + "\",\"aboutDeliveryId\":\""
        + stopTwo + "\"}", 422);

    UUID requestId = UUID.fromString(asked.get("requestId").asText());
    answer(storeTwo, requestId, 1, "{\"answer\":\"yes\"}", 200);
    drain();
    assertTrue(driverThreadSays(
        NEXT_OUTLET + " agreed: unload the goods for " + OUTLET + " at their dock to reach theirs, then reload them."));
  }

  @Test
  void noAnswerByTheDeadlineTellsTheDispatcherToPhoneWhoRecordsWhatTheStoreSaid() throws Exception {
    // The expiry job looks at every depot and day: settle what other tests left before counting.
    at("07:40");
    expiry.runAt(clock.now());
    drain();
    long told = notified(dispatcherId, "decision.expired");
    at("07:10");
    UUID first = UUID.fromString(json(ask(dispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne
        + "\",\"receiveAt\":\"" + instant("08:20") + "\"}", 200)).get("result").get("requestId").asText());
    // Asking again about the same stop replaces the open question.
    UUID requestId = UUID.fromString(json(ask(dispatcher, "{\"kind\":\"receive_late\",\"deliveryId\":\"" + stopOne
        + "\",\"receiveAt\":\"" + instant("08:30") + "\"}", 200)).get("result").get("requestId").asText());
    assertEquals("withdrawn", decision(dispatcher, first).get("state").asText());
    answer(storeOne, first, 2, "{\"answer\":\"yes\"}", 409);

    at("07:40");
    assertEquals(1, expiry.runAt(clock.now()));
    assertEquals(0, expiry.runAt(clock.now()), "once");
    drain();
    assertEquals("expired", decision(dispatcher, requestId).get("state").asText());
    assertEquals(told + 1, notified(dispatcherId, "decision.expired"));

    // The dispatcher phones and records the store's answer; it came after the deadline.
    answer(storeOne, requestId, 2, "{\"answer\":\"no\",\"byPhone\":true}", 403);
    JsonNode phoned = json(answer(dispatcher, requestId, 2, "{\"answer\":\"no\",\"byPhone\":true,\"reason\":\"Closed at 08:00\"}", 200));
    assertTrue(phoned.get("result").get("late").asBoolean());
    JsonNode seen = decision(dispatcher, requestId);
    assertEquals("phone", seen.get("answeredVia").asText());
    assertEquals("no", seen.get("answer").asText());
    drain();
    assertFalse(driverThreadSays(OUTLET + " will receive at about 08:30."), "a No is the dispatcher's to act on");
  }

  // ---- the system asks by itself (R-ISS-15) ----

  @Test
  void whenTheWatchFindsAWindowAtRiskTheSystemAsksTheStoreOnceAndTellsTheDispatcher() throws Exception {
    raised(stopOne, OUTLET, "WINDOW_AT_RISK");
    drain();
    JsonNode asked = decisions(storeOne);
    assertEquals(1, asked.size(), "asked by the system, nobody pressed anything");
    assertEquals("receive_late", asked.get(0).get("kind").asText());
    assertEquals(1, notified(storeOneId, "decision.requested"));
    assertEquals(1, notified(dispatcherId, "attention.raised"), "the dispatcher's bell, Live screen open or not");

    // Found again, or refound after an answer: never asked twice.
    raised(stopOne, OUTLET, "WINDOW_AT_RISK");
    drain();
    assertEquals(1, decisions(storeOne).size());

    // A mall is never asked: its window is fixed.
    raised(mallStop, MALL_OUTLET, "WINDOW_AT_RISK");
    drain();
    assertEquals(1, decisions(dispatcher).size());
  }

  @Test
  void whenTheWatchFindsAStopNotDeliveredTheNextStoreIsAskedToHelpAtItsDock() throws Exception {
    raised(stopOne, OUTLET, "FAILED_STOP");
    drain();
    JsonNode asked = decisions(storeTwo);
    assertEquals(1, asked.size());
    assertEquals("dock_help", asked.get(0).get("kind").asText());
    assertEquals(OUTLET, asked.get(0).get("aboutOutletId").asText());
    assertEquals(0, notified(dispatcherId, "attention.raised"), "a failed stop is told by delivery.failed, not twice");
  }

  private void raised(UUID deliveryId, String outletId, String kind) {
    publish(ModuleRole.ML, new com.waypoint.dispatch.intelligence.contract.AttentionEvents.AttentionRaised(
        deliveryId, kind, "CRITICAL", depot, day, vehicleId, tripId, outletId, Optional.of(-5), clock.now()));
  }

  // ---- helpers ----

  private String ask(String who, String payload, int expected) throws Exception {
    return command(who, "issue:AskDecision", null, payload, expected);
  }

  private String answer(String who, UUID requestId, long version, String payload, int expected) throws Exception {
    return command(who, "issue:AnswerDecision", version,
        payload.replaceFirst("\\{", "{\"requestId\":\"" + requestId + "\","), expected);
  }

  private JsonNode decisions(String who) throws Exception {
    return json(read(who, "/api/issues/decisions?date=" + day, 200));
  }

  private JsonNode decision(String who, UUID requestId) throws Exception {
    for (JsonNode d : decisions(who)) {
      if (d.get("requestId").asText().equals(requestId.toString())) return d;
    }
    throw new AssertionError("no decision " + requestId);
  }

  private boolean driverThreadSays(String body) throws Exception {
    String thread = json(read(driver, "/api/threads/by-subject?type=trip&id=" + tripId, 200)).get("threadId").asText();
    for (JsonNode m : json(read(driver, "/api/threads/" + thread + "/messages", 200)).get("items")) {
      if (body.equals(m.get("body").asText())) return true;
    }
    return false;
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
