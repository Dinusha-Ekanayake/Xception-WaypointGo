package com.waypoint.dispatch.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
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
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * Execution end to end. A plan is published, loaded and released as events
 * through the real relay, which builds the driver's run sheet; the driver then
 * works the stops through {@code POST /api/commands} with a real session, and
 * the relay carries the outcomes back to Ordering.
 *
 * <p>The clock is the test's, so early, on time and late are chosen, not hoped
 * for. Sessions end by the same clock, so every call signs in again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExecutionTestConfig.class)
@ExtendWith(TestDatabase.class)
class ExecutionIntegrationTest {
  /** Window 05:00 to 07:30, Peliyagoda. */
  static final String OUTLET = "OUT001";
  /** A mall outlet of the same depot, window 09:00 to 11:00. */
  static final String MALL_OUTLET = "OUT015";
  static final String OTHER_DEPOT_OUTLET = "OUT076";
  static final String PASSWORD = "OnTheRoad2026!";
  static final byte[] PNG = png(40);

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
  String otherVehicleId;
  String manager;
  String driver;
  String otherDriver;
  String yesterdaysDriver;
  String dispatcher;
  String farDispatcher;
  UUID driverId;
  UUID planId;
  UUID tripId;
  UUID orderA;
  UUID orderB;
  UUID mallOrder;
  UUID stopA;
  UUID stopB;
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
    // Other test classes leave events pending; settle them so the relay here
    // delivers this test's events and nobody else's.
    database.asSystemSeparately(
        ModuleRole.INTEGRATION,
        () -> database.update(
            "UPDATE integration.outbox_events SET status = 'published'"
                + " WHERE status IN ('pending','failed','processing')"));

    depot = reference.outlet(OUTLET, null).orElseThrow().depotCode();
    day = reference.nextOperatingDay(
        LocalDate.of(2040, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));
    clock.set(day.minusDays(1).atTime(LocalTime.of(10, 0)).atZone(Clock.OPERATING_ZONE).toInstant());

    var vehicles = reference.availableVehicles(depot, day, null);
    vehicleId = vehicles.get(0).vehicleId();
    otherVehicleId = vehicles.get(1).vehicleId();

    String run = UUID.randomUUID().toString().substring(0, 8);
    manager = "xm-" + run + "@execution.test";
    driver = "xd-" + run + "@execution.test";
    otherDriver = "xo-" + run + "@execution.test";
    yesterdaysDriver = "xy-" + run + "@execution.test";
    dispatcher = "xp-" + run + "@execution.test";
    farDispatcher = "xf-" + run + "@execution.test";
    UUID managerId = accounts.createAccount(manager, "Store", PASSWORD, "store_manager");
    driverId = accounts.createAccount(driver, "Driver", PASSWORD, "driver");
    UUID otherDriverId = accounts.createAccount(otherDriver, "Other Driver", PASSWORD, "driver");
    UUID yesterdaysDriverId = accounts.createAccount(yesterdaysDriver, "Yesterday", PASSWORD, "driver");
    accounts.createAccount(dispatcher, "Dispatcher", PASSWORD, "dispatcher");
    accounts.createAccount(farDispatcher, "Far Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(dispatcher, depot);
    accounts.grantDepot(farDispatcher, reference.outlet(OTHER_DEPOT_OUTLET, null).orElseThrow().depotCode());
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          for (String outlet : List.of(OUTLET, MALL_OUTLET)) {
            database.update(
                "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)", managerId, outlet);
          }
          assign(driverId, vehicleId, day, day.plusDays(1));
          assign(otherDriverId, otherVehicleId, day, day.plusDays(1));
          // Drove this vehicle the day before, and not today (EXE-13).
          assign(yesterdaysDriverId, vehicleId, day.minusDays(1), day);
        });

    orderA = place(OUTLET, "[{\"productId\":\"P-1\",\"quantity\":40}]");
    orderB = place(OUTLET, "[{\"productId\":\"P-3\",\"quantity\":4}]");
    mallOrder = place(MALL_OUTLET, "[{\"productId\":\"P-4\",\"quantity\":6}]");

    // The trip is planned, loaded and released: three events, delivered by the relay.
    at("04:40");
    planId = UUID.randomUUID();
    tripId = UUID.randomUUID();
    publish(ModuleRole.PLANNING, new PlanPublished(planId, depot, day, 1, Optional.empty(), List.of(plannedTrip())));
    publish(ModuleRole.LOADING, new LoadingStarted(tripId, planId, vehicleId));
    publish(
        ModuleRole.LOADING,
        new TripReleased(
            tripId, planId, 1, vehicleId, depot, day,
            List.of(
                new ReleasedStop(1, orderA, OUTLET, LocalTime.of(5, 20)),
                new ReleasedStop(2, orderB, OUTLET, LocalTime.of(5, 40)),
                new ReleasedStop(3, mallOrder, MALL_OUTLET, LocalTime.of(9, 30)))));
    drain();

    JsonNode stops = runSheet(driver).get("stops");
    stopA = UUID.fromString(stops.get(0).get("deliveryId").asText());
    stopB = UUID.fromString(stops.get(1).get("deliveryId").asText());
    mallStop = UUID.fromString(stops.get(2).get("deliveryId").asText());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  // ---- the run sheet ----

  @Test
  void aReleasedTripBecomesTheDriversRunSheetAndMovesItsOrdersInTransit() throws Exception {
    JsonNode sheet = runSheet(driver);
    assertEquals(vehicleId, sheet.get("vehicleId").asText());
    assertEquals(3, sheet.get("stops").size());

    JsonNode first = sheet.get("stops").get(0);
    assertEquals(orderA.toString(), first.get("orderId").asText());
    assertEquals(1, first.get("sequence").asInt());
    assertEquals(40, first.get("itemCount").asInt(), "the unit count is the order's, read from Ordering");
    assertEquals("05:00:00", first.get("windowOpen").asText(), "the window is copied from Reference at release");
    assertEquals("07:30:00", first.get("windowClose").asText());
    assertEquals("05:20:00", first.get("plannedArrival").asText());
    assertEquals("PENDING", first.get("outcome").asText());
    assertEquals(1, first.get("rowVersion").asLong());
    assertFalse(first.get("mallOutlet").asBoolean());
    assertTrue(sheet.get("stops").get(2).get("mallOutlet").asBoolean());

    assertEquals("IN_TRANSIT", orderStatus(orderA), "the same event moved the order, through the relay");
    assertEquals(List.of(vehicleId), strings(json(read(driver, "/api/execution/vehicles?date=" + day, 200))));
  }

  @Test
  void aRedeliveredReleaseDoesNotRebuildOrResetTheRunSheet() throws Exception {
    send(driver, "delivery:Start", 1L, stop(stopA), 200);

    publish(
        ModuleRole.LOADING,
        new TripReleased(
            tripId, planId, 1, vehicleId, depot, day,
            List.of(new ReleasedStop(1, orderA, OUTLET, LocalTime.of(5, 20)))));
    drain();

    JsonNode sheet = runSheet(driver);
    assertEquals(3, sheet.get("stops").size());
    assertEquals(2, sheet.get("stops").get(0).get("rowVersion").asLong());
  }

  @Test
  void eachRoleSeesItsOwnScopeAndNothingElse() throws Exception {
    // The driver of another vehicle has a day with no stops, not this driver's stops.
    JsonNode others = json(read(otherDriver, "/api/execution/run-sheets?date=" + day, 200));
    assertEquals(1, others.size());
    assertEquals(otherVehicleId, others.get(0).get("vehicleId").asText());
    assertEquals(0, others.get(0).get("stops").size());
    read(otherDriver, "/api/execution/deliveries/" + stopA, 404);
    assertEquals(0, json(read(yesterdaysDriver, "/api/execution/run-sheets?date=" + day, 200)).size());

    // A dispatcher sees the depot's vehicles on the road.
    JsonNode live = json(read(dispatcher, "/api/execution/run-sheets?date=" + day + "&depot=" + depot, 200));
    assertEquals(1, live.size());
    assertEquals(3, live.get(0).get("stops").size());

    // Another depot's dispatcher is refused, and the refusal is recorded: not an empty list.
    read(farDispatcher, "/api/execution/run-sheets?date=" + day + "&depot=" + depot, 403);
    assertTrue(audited("delivery:Read", "DENY", "wpt:execution:depot:" + depot));
    read(farDispatcher, "/api/execution/deliveries/" + stopA, 404);

    // A store manager sees what is coming to their outlet.
    JsonNode coming = json(read(manager, "/api/execution/deliveries?outlet=" + OUTLET + "&date=" + day, 200));
    assertEquals(2, coming.size());
    read(manager, "/api/execution/deliveries?outlet=" + OTHER_DEPOT_OUTLET + "&date=" + day, 403);
    assertEquals(
        stopA.toString(),
        json(read(manager, "/api/execution/deliveries?order=" + orderA, 200)).get(0).get("deliveryId").asText());
  }

  @Test
  void rowLevelSecurityHidesAStopFromAnActorWithNoScope() {
    UUID nobody = UUID.randomUUID();
    assertTrue(
        database.asModule(
            ModuleRole.EXECUTION,
            nobody,
            () -> database.query("SELECT 1 FROM execution.delivery_records WHERE delivery_id = ?", stopA).isEmpty()));
    assertTrue(
        database.asModule(
            ModuleRole.EXECUTION,
            nobody,
            () -> database.query("SELECT 1 FROM execution.trips WHERE trip_id = ?", tripId).isEmpty()));
    assertFalse(
        database.asModule(
            ModuleRole.EXECUTION,
            driverId,
            () -> database.query("SELECT 1 FROM execution.delivery_records WHERE delivery_id = ?", stopA).isEmpty()));
  }

  // ---- working a stop ----

  @Test
  void aStopIsStartedReachedAndDeliveredAndTheOrderFollows() throws Exception {
    at("04:50");
    assertEquals(2, version(send(driver, "delivery:Start", 1L, stop(stopA), 200)));
    assertEquals(1, outbox("delivery.started", stopA));

    // Ten minutes before the window opens: the vehicle waits (EXE-05).
    JsonNode arrived = json(send(driver, "delivery:RecordArrival", 2L, stop(stopA), 200)).get("result");
    assertEquals(3, arrived.get("rowVersion").asLong());
    assertEquals("ARRIVED", arrived.get("outcome").asText());
    assertEquals(10, arrived.get("waitMinutes").asInt());
    assertEquals(0, arrived.get("lateMinutes").asInt());

    at("05:15");
    JsonNode delivered =
        json(send(driver, "delivery:Record", 3L, record(stopA, "DELIVERED", null, null, null), 200)).get("result");
    assertEquals(4, delivered.get("rowVersion").asLong(), "each command moves the version on by exactly one");
    assertEquals("DELIVERED", delivered.get("outcome").asText());

    JsonNode view = json(read(driver, "/api/execution/deliveries/" + stopA, 200));
    assertEquals(40, view.get("deliveredUnits").asInt());
    assertEquals(instant("04:50"), view.get("arrivedAt").asText());
    assertEquals(instant("05:00"), view.get("serviceStartedAt").asText(), "service starts when the window opens");
    assertEquals(instant("05:15"), view.get("completedAt").asText());
    assertFalse(view.get("timingUncertain").asBoolean());

    assertEquals(1, outbox("delivery.completed", stopA));
    drain();
    assertEquals("DELIVERED", orderStatus(orderA));
  }

  @Test
  void aLateStopIsStillDeliveredWithAReasonAndTheEventSaysHowLate() throws Exception {
    // EXE-06, R-EXE-05, R-EXE-14: the window closed at 07:30.
    at("07:50");
    JsonNode arrived = json(send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200)).get("result");
    assertEquals(20, arrived.get("lateMinutes").asInt());

    String refused = send(driver, "delivery:Record", 2L, record(stopA, "DELIVERED", null, null, null), 409);
    assertTrue(refused.contains("R-EXE-05"), refused);

    send(driver, "delivery:Record", 2L, record(stopA, "DELIVERED", null, "Accident on the Kandy road", null), 200);
    JsonNode view = json(read(dispatcher, "/api/execution/deliveries/" + stopA, 200));
    assertEquals("DELIVERED", view.get("outcome").asText());
    assertEquals(20, view.get("lateMinutes").asInt());
    assertEquals("Accident on the Kandy road", view.get("lateReason").asText());
    assertEquals(20, payload("delivery.completed", stopA).get("lateMinutes").asInt());
  }

  @Test
  void aPartialDeliveryCountsUnitsAndAFailedOneAccountsForTheGoods() throws Exception {
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);
    send(driver, "delivery:Record", 2L, record(stopA, "PARTIAL", 38, "Two cartons crushed", null), 409);
    send(driver, "delivery:Record", 2L, record(stopA, "PARTIAL", 40, "Two cartons crushed", "On the vehicle"), 409);
    send(driver, "delivery:Record", 2L, record(stopA, "PARTIAL", 38, "Two cartons crushed", "On the vehicle"), 200);

    // The outlet refused the second order outright (EXE-07).
    send(driver, "delivery:RecordArrival", 1L, stop(stopB), 200);
    send(driver, "delivery:Record", 2L, record(stopB, "FAILED", null, "gone fishing", "On the vehicle"), 422);
    String noNote = send(driver, "delivery:Record", 2L, record(stopB, "FAILED", null, "refused", null), 409);
    assertTrue(noNote.contains("R-EXE-12"), noNote);
    send(driver, "delivery:Record", 2L, record(stopB, "FAILED", null, "refused", "Returned to the depot"), 200);

    JsonNode failed = payload("delivery.failed", stopB);
    assertEquals("refused", failed.get("reason").asText());
    assertEquals(depot, failed.get("depotCode").asText());
    assertEquals(38, payload("delivery.completed", stopA).get("deliveredUnits").asInt());

    drain();
    assertEquals("PARTIALLY_DELIVERED", orderStatus(orderA));
    assertEquals("FAILED", orderStatus(orderB));
  }

  @Test
  void anOutcomeIsRecordedOnceAndADeliveryNeedsAnArrival() throws Exception {
    at("05:30");
    String early = send(driver, "delivery:Record", 1L, record(stopA, "DELIVERED", null, null, null), 409);
    assertTrue(early.contains("R-EXE-04"), early);

    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);
    send(driver, "delivery:Record", 2L, record(stopA, "DELIVERED", null, null, null), 200);
    String again = send(driver, "delivery:Record", 3L, record(stopA, "FAILED", null, "refused", "x"), 409);
    assertTrue(again.contains("R-EXE-01"), again);
    assertEquals("DELIVERED", json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("outcome").asText());
    assertEquals(0, outbox("delivery.failed", stopA));
  }

  @Test
  void aLateArrivalAtAMallIsFailedNotDelivered() throws Exception {
    // EXE-20: the mall's window is 09:00 to 11:00.
    at("11:20");
    send(driver, "delivery:RecordArrival", 1L, stop(mallStop), 200);

    String refused = send(driver, "delivery:Record", 2L, record(mallStop, "DELIVERED", null, "Traffic", null), 409);
    assertTrue(refused.contains("R-PLN-14"), refused);

    send(driver, "delivery:Record", 2L, record(mallStop, "FAILED", null, "mall_window_closed", "On the vehicle"), 200);
    assertEquals("mall_window_closed", payload("delivery.failed", mallStop).get("reason").asText());
  }

  // ---- versions, replays and clocks ----

  @Test
  void aStaleVersionIsAConflictAndARetriedCommandIsAnsweredOnce() throws Exception {
    at("05:10");
    UUID commandId = UUID.randomUUID();
    String body = envelope(commandId, "delivery:RecordArrival", 1L, stop(stopA), clock.now());

    JsonNode first = json(command(driver, body, 200));
    JsonNode retry = json(command(driver, body, 200));
    assertFalse(first.get("replayed").asBoolean());
    assertTrue(retry.get("replayed").asBoolean(), "the same command id is answered from its receipt");
    assertEquals(first.get("result"), retry.get("result"));

    // EXE-14: a second device still holding version 1 is told, never merged.
    String conflict = send(driver, "delivery:RecordArrival", 1L, stop(stopA), 409);
    assertTrue(conflict.contains("VERSION_CONFLICT"), conflict);
    send(driver, "delivery:Start", null, stop(stopB), 422);
    assertEquals(2, json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("rowVersion").asLong());
  }

  @Test
  void aStopRecordedOfflineAndReplayedThroughSyncAppliesOnce() throws Exception {
    // EXE-02: the device queued the arrival and the delivery, then found signal.
    at("05:30");
    String arrive = envelope(UUID.randomUUID(), "delivery:RecordArrival", 1L, stop(stopA), clock.now());
    String deliver =
        envelope(UUID.randomUUID(), "delivery:Record", 2L, record(stopA, "DELIVERED", null, null, null), clock.now());
    String batch =
        "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"operations\":[{\"sequence\":1,\"command\":" + arrive
            + "},{\"sequence\":2,\"command\":" + deliver + "}]}";

    JsonNode first = json(sync(driver, batch)).get("results");
    JsonNode second = json(sync(driver, batch)).get("results");

    assertEquals("APPLIED", first.get(0).get("status").asText());
    assertEquals("APPLIED", first.get(1).get("status").asText());
    assertTrue(second.get(0).get("replayed").asBoolean());
    assertTrue(second.get(1).get("replayed").asBoolean());
    assertEquals(3, json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("rowVersion").asLong());
    assertEquals(1, outbox("delivery.completed", stopA));
  }

  @Test
  void theServersClockDecidesWhenTheDevicesIsFarOff() throws Exception {
    // EXE-12, R-EXE-10: recorded on the device at 06:00, accepted by the server at 12:00.
    at("12:00");
    Instant deviceTime = day.atTime(LocalTime.of(6, 0)).atZone(Clock.OPERATING_ZONE).toInstant();
    String body = envelope(UUID.randomUUID(), "delivery:RecordArrival", 1L, stop(stopA), deviceTime);

    JsonNode result = json(command(driver, body, 200)).get("result");
    assertTrue(result.get("timingUncertain").asBoolean());
    assertEquals(270, result.get("lateMinutes").asInt(), "by the server's clock, against the 07:30 close");

    // No reason is demanded for lateness the driver may not have caused.
    send(driver, "delivery:Record", 2L, record(stopA, "DELIVERED", null, null, null), 200);
    JsonNode view = json(read(driver, "/api/execution/deliveries/" + stopA, 200));
    assertEquals(instant("12:00"), view.get("arrivedAt").asText());
    assertEquals("recorded_after_reconnect", view.get("lateReason").asText());
    assertEquals(0, outbox("eta.changed", stopB), "an uncertain time announces no delay to the stops ahead");
    Map<String, Object> row = row(stopA);
    assertEquals(deviceTime, ((java.sql.Timestamp) row.get("client_arrived_at")).toInstant(), "kept for forensics");
  }

  @Test
  void aTripRunningBehindTellsTheStopsAhead() throws Exception {
    // R-EXE-15: planned 05:20, reached 05:55.
    at("05:55");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);

    JsonNode eta = payload("eta.changed", stopB);
    assertEquals(35, eta.get("delayMinutes").asInt());
    assertEquals(instant("06:15"), eta.get("expectedArrival").asText());
    assertEquals(1, outbox("eta.changed", mallStop));
    assertEquals(0, outbox("eta.changed", stopA), "the stop already reached is not told about itself");
    assertEquals(instant("06:15"), runSheet(driver).get("stops").get(1).get("expectedArrival").asText());

    // Four more minutes of delay is not news.
    at("06:19");
    send(driver, "delivery:RecordArrival", 1L, stop(stopB), 200);
    assertEquals(1, outbox("eta.changed", mallStop));
  }

  // ---- who may record ----

  @Test
  void onlyTodaysDriverOfTheVehicleRecordsAndADenialIsAudited() throws Exception {
    // EXE-13
    at("05:10");
    String other = send(otherDriver, "delivery:RecordArrival", 1L, stop(stopA), 403);
    assertTrue(other.contains("FORBIDDEN"), other);
    send(yesterdaysDriver, "delivery:RecordArrival", 1L, stop(stopA), 403);
    // A dispatcher reads this stop and still may not record for it: policy has no delivery:Record for them.
    send(dispatcher, "delivery:Record", 1L, record(stopA, "FAILED", null, "refused", "x"), 403);

    // EXE-15: a stop that is on no trip of theirs, or on no trip at all, is the same refusal.
    send(driver, "delivery:RecordArrival", 1L, stop(UUID.randomUUID()), 403);

    assertTrue(audited("delivery:RecordArrival", "DENY", "wpt:execution:delivery:" + stopA));
    assertEquals("PENDING", json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("outcome").asText());
    assertEquals(1, json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("rowVersion").asLong());
  }

  // ---- proof ----

  @Test
  void proofIsUploadedCapturedAndReadBackThroughALinkThatExpires() throws Exception {
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);
    send(driver, "delivery:Record", 2L, record(stopA, "DELIVERED", null, null, null), 200);
    UUID photo = UUID.randomUUID();

    JsonNode stored = json(upload(driver, stopA, photo, "photo", PNG, 200));
    assertTrue(stored.get("stored").asBoolean());
    assertEquals("image/png", stored.get("contentType").asText());
    assertFalse(json(upload(driver, stopA, photo, "photo", PNG, 200)).get("stored").asBoolean(), "the same upload twice is one artifact");
    upload(driver, stopA, photo, "photo", png(41), 409);

    JsonNode captured =
        json(send(driver, "delivery:CaptureProof", 3L, proof(stopA, photo, null, "N. Perera", null), 200)).get("result");
    assertEquals(4, captured.get("rowVersion").asLong());
    assertFalse(captured.get("lowEvidence").asBoolean());
    assertFalse(captured.get("uploadsPending").asBoolean());

    // The dispatcher reads the proof and follows its link.
    JsonNode view = json(read(dispatcher, "/api/execution/deliveries/" + stopA + "/proof", 200));
    assertEquals("N. Perera", view.get("recipientName").asText());
    String link = view.get("photoUrl").asText();
    MvcResult image = http.perform(get(link)).andReturn();
    assertEquals(200, image.getResponse().getStatus());
    assertEquals("image/png", image.getResponse().getContentType());
    assertArrayEquals(PNG, image.getResponse().getContentAsByteArray());
    assertEquals("nosniff", image.getResponse().getHeader("X-Content-Type-Options"));

    // Forged, stretched and expired links all answer the same.
    assertEquals(404, http.perform(get(link.substring(0, link.length() - 2) + "00")).andReturn().getResponse().getStatus());
    assertEquals(404, http.perform(get(link.replaceFirst("exp=\\d+", "exp=9999999999"))).andReturn().getResponse().getStatus());
    clock.set(clock.now().plusSeconds(301));
    assertEquals(404, http.perform(get(link)).andReturn().getResponse().getStatus());

    assertTrue(runSheet(driver).get("stops").get(0).get("proofCaptured").asBoolean());
    assertEquals("DELIVERED", json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("outcome").asText());
  }

  @Test
  void aDeviceThatCannotCaptureCarriesOnWithAReasonAndLowerEvidence() throws Exception {
    // EXE-09, R-EXE-11
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);

    String refused = send(driver, "delivery:CaptureProof", 2L, proof(stopA, null, null, "N. Perera", null), 409);
    assertTrue(refused.contains("R-EXE-11"), refused);

    JsonNode captured =
        json(send(driver, "delivery:CaptureProof", 2L, proof(stopA, null, null, "N. Perera", "Camera permission denied"), 200))
            .get("result");
    assertTrue(captured.get("lowEvidence").asBoolean());
    assertTrue(json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("lowEvidence").asBoolean());

    // A later capture with a signature replaces it as the stop's proof; the first stays on record.
    UUID signature = UUID.randomUUID();
    upload(driver, stopA, signature, "signature", PNG, 200);
    send(driver, "delivery:CaptureProof", 3L, proof(stopA, null, signature, "N. Perera", null), 200);
    assertFalse(json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("lowEvidence").asBoolean());
    assertEquals(2L, count("SELECT count(*) AS n FROM execution.proofs WHERE delivery_id = ?", stopA));
  }

  @Test
  void proofRecordedBeforeItsPhotoArrivesIsShownAsPendingUntilItDoes() throws Exception {
    // EXE-10: no signal for the upload; the proof itself is not held back.
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);
    UUID photo = UUID.randomUUID();

    JsonNode captured =
        json(send(driver, "delivery:CaptureProof", 2L, proof(stopA, photo, null, null, null), 200)).get("result");
    assertTrue(captured.get("uploadsPending").asBoolean());
    JsonNode pending = json(read(driver, "/api/execution/deliveries/" + stopA + "/proof", 200));
    assertTrue(pending.get("photoPending").asBoolean());
    assertTrue(pending.get("photoUrl").isNull());

    upload(driver, stopA, photo, "photo", PNG, 200);
    JsonNode arrived = json(read(driver, "/api/execution/deliveries/" + stopA + "/proof", 200));
    assertFalse(arrived.get("photoPending").asBoolean());
    assertFalse(arrived.get("photoUrl").isNull());
  }

  @Test
  void anUploadIsRefusedFromAnotherDriverForAnotherStopOrForSomethingThatIsNotAnImage() throws Exception {
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);
    UUID photo = UUID.randomUUID();

    upload(otherDriver, stopA, photo, "photo", PNG, 403);
    assertTrue(audited("delivery:CaptureProof", "DENY", "wpt:execution:delivery:" + stopA));
    upload(dispatcher, stopA, photo, "photo", PNG, 403);
    upload(driver, stopA, photo, "photo", "<svg onload=alert(1)>".getBytes(), 422);
    upload(driver, stopA, photo, "video", PNG, 422);
    upload(driver, stopA, photo, "photo", new byte[3_200_000], 413);

    // An id used for one stop cannot be claimed for another.
    upload(driver, stopA, photo, "photo", PNG, 200);
    upload(driver, stopB, photo, "photo", PNG, 409);
    send(driver, "delivery:RecordArrival", 1L, stop(stopB), 200);
    send(driver, "delivery:CaptureProof", 2L, proof(stopB, photo, null, null, null), 422);
    send(driver, "delivery:CaptureProof", 2L, proof(stopA, null, photo, null, null), 422);

    // Proof is for a stop the vehicle has reached.
    String early = send(driver, "delivery:CaptureProof", 1L, proof(mallStop, null, null, null, "No camera"), 409);
    assertTrue(early.contains("R-EXE-01"), early);
  }

  // ---- reports and replans ----

  @Test
  void aFaultIsReportedAndAnnouncedAndChangesNoStop() throws Exception {
    at("06:00");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);

    send(driver, "delivery:ReportFault", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"kind\":\"vehicle\",\"description\":\"Engine overheating\"}", 200);
    JsonNode fault = payloadFor("vehicle.fault_reported", vehicleId);
    assertEquals(depot, fault.get("depotCode").asText());
    assertEquals(day.toString(), fault.get("serviceDate").asText());
    assertEquals("Engine overheating", fault.get("description").asText());

    send(driver, "delivery:ReportFault", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"deliveryId\":\"" + stopA
            + "\",\"kind\":\"road\",\"description\":\"Road closed at the bridge\"}", 200);
    assertEquals(
        reference.outlet(OUTLET, null).orElseThrow().districtName(),
        payloadFor("road.disruption_reported", vehicleId).get("districtName").asText(),
        "a report made at a stop says where");

    send(driver, "delivery:ReportVehicleStatus", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"status\":\"at_workshop\",\"note\":null}", 200);
    send(driver, "delivery:ReportVehicleStatus", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"status\":\"fault\",\"note\":null}", 422);
    send(driver, "delivery:ReportVehicleStatus", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"status\":\"flying\",\"note\":null}", 422);
    assertEquals(2L, count("SELECT count(*) AS n FROM execution.vehicle_reports WHERE vehicle_id = ? AND service_date = ?", vehicleId, Date.valueOf(day)));

    // Not this driver's vehicle, and not a vehicle at all: one refusal for both.
    send(driver, "delivery:ReportFault", null,
        "{\"vehicleId\":\"" + otherVehicleId + "\",\"kind\":\"vehicle\",\"description\":\"x\"}", 403);
    send(driver, "delivery:ReportFault", null,
        "{\"vehicleId\":\"VEH-NOPE\",\"kind\":\"vehicle\",\"description\":\"x\"}", 403);
    send(yesterdaysDriver, "delivery:ReportVehicleStatus", null,
        "{\"vehicleId\":\"" + vehicleId + "\",\"status\":\"available\",\"note\":null}", 403);

    assertEquals("ARRIVED", json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("outcome").asText());
    assertEquals(2, json(read(driver, "/api/execution/deliveries/" + stopA, 200)).get("rowVersion").asLong());
  }

  @Test
  void aReplanSkipsTheStopsNobodyReachedAndHoldsWorkQueuedAgainstThem() throws Exception {
    // EXE-08, EXE-03: after a breakdown the dispatcher moves the second order off this trip.
    at("05:30");
    send(driver, "delivery:RecordArrival", 1L, stop(stopA), 200);

    PlannedTrip remaining =
        new PlannedTrip(
            tripId, vehicleId, 1, "Fresh", "Colombo", "ambient", LocalTime.of(4, 30),
            List.of(
                new PlannedStop(1, orderA, OUTLET, LocalTime.of(5, 20)),
                new PlannedStop(2, mallOrder, MALL_OUTLET, LocalTime.of(9, 30))));
    publish(
        ModuleRole.PLANNING,
        new PlanRevised(UUID.randomUUID(), depot, day, 2, planId, "vehicle fault", List.of(remaining)));
    drain();

    JsonNode stops = runSheet(driver).get("stops");
    assertEquals("ARRIVED", stops.get(0).get("outcome").asText(), "what happened at a stop already reached stands");
    assertEquals("SKIPPED", stops.get(1).get("outcome").asText());
    assertEquals(2, stops.get(1).get("rowVersion").asLong());
    assertEquals("PENDING", stops.get(2).get("outcome").asText());
    assertEquals("replanned", json(read(driver, "/api/execution/deliveries/" + stopB, 200)).get("failureReason").asText());

    // What the driver queued against the skipped stop is a conflict, not a delivery.
    send(driver, "delivery:RecordArrival", 1L, stop(stopB), 409);
    String refused = send(driver, "delivery:RecordArrival", 2L, stop(stopB), 409);
    assertTrue(refused.contains("R-EXE-01"), refused);
  }

  // ---- helpers ----

  private void assign(UUID driverUserId, String vehicle, LocalDate from, LocalDate until) {
    database.update(
        "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
            + " VALUES (?, ?, daterange(?::date, ?::date))",
        vehicle, driverUserId, Date.valueOf(from), Date.valueOf(until));
  }

  private PlannedTrip plannedTrip() {
    return new PlannedTrip(
        tripId, vehicleId, 1, "Fresh", "Colombo", "ambient", LocalTime.of(4, 30),
        List.of(
            new PlannedStop(1, orderA, OUTLET, LocalTime.of(5, 20)),
            new PlannedStop(2, orderB, OUTLET, LocalTime.of(5, 40)),
            new PlannedStop(3, mallOrder, MALL_OUTLET, LocalTime.of(9, 30))));
  }

  /** Sets the clock to a time on the service day, in the operating zone. */
  private void at(String time) {
    clock.set(day.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant());
  }

  private String instant(String time) {
    return day.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant().toString();
  }

  private void publish(ModuleRole role, DomainEvent event) {
    database.asSystem(role, () -> publisher.publish(Actor.SYSTEM, event));
  }

  /** What the relay worker does in a running instance. */
  private void drain() {
    for (int pass = 0; pass < 100; pass++) {
      if (relay.deliverBatch() == 0) {
        long stuck =
            ((Number)
                    database
                        .asSystemSeparately(
                            ModuleRole.INTEGRATION,
                            () -> database.queryOne(
                                "SELECT count(*) AS n FROM integration.outbox_events"
                                    + " WHERE status IN ('failed','dead') AND occurred_at >= ?",
                                java.sql.Timestamp.from(clock.now().minusSeconds(86_400 * 2))))
                        .get("n"))
                .longValue();
        assertEquals(0L, stuck, "an event of this test failed delivery: " + failures());
        return;
      }
    }
    throw new AssertionError("the relay never ran out of work");
  }

  private String failures() {
    return String.valueOf(
        database.asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.query(
                "SELECT event_type, last_error FROM integration.outbox_events WHERE status IN ('failed','dead')"
                    + " ORDER BY seq DESC LIMIT 5")));
  }

  private UUID place(String outlet, String lines) throws Exception {
    String body =
        envelope(
            UUID.randomUUID(), "order:Place", null,
            "{\"outletId\":\"" + outlet + "\",\"requestedDate\":\"" + day + "\",\"lines\":" + lines + "}",
            clock.now());
    return UUID.fromString(json(command(manager, body, 200)).get("result").get("orderId").asText());
  }

  private String orderStatus(UUID orderId) throws Exception {
    return json(read(manager, "/api/orders/" + orderId, 200)).get("status").asText();
  }

  private JsonNode runSheet(String who) throws Exception {
    JsonNode sheets = json(read(who, "/api/execution/run-sheets?date=" + day, 200));
    assertEquals(1, sheets.size(), sheets.toString());
    return sheets.get(0);
  }

  private static String stop(UUID deliveryId) {
    return "{\"deliveryId\":\"" + deliveryId + "\"}";
  }

  private static String record(UUID deliveryId, String outcome, Integer units, String reason, String note) {
    return "{\"deliveryId\":\"" + deliveryId + "\",\"outcome\":\"" + outcome + "\",\"deliveredUnits\":" + units
        + ",\"reason\":" + quoted(reason) + ",\"dispositionNote\":" + quoted(note) + "}";
  }

  private static String proof(UUID deliveryId, UUID photo, UUID signature, String recipient, String fallback) {
    return "{\"deliveryId\":\"" + deliveryId + "\",\"photoAttachmentId\":" + quoted(photo)
        + ",\"signatureAttachmentId\":" + quoted(signature) + ",\"recipientName\":" + quoted(recipient)
        + ",\"fallbackReason\":" + quoted(fallback) + "}";
  }

  private static String quoted(Object value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private long version(String response) throws Exception {
    return json(response).get("result").get("rowVersion").asLong();
  }

  private String send(String who, String kind, Long version, String payload, int expected) throws Exception {
    return command(who, envelope(UUID.randomUUID(), kind, version, payload, clock.now()), expected);
  }

  private static String envelope(UUID id, String kind, Long version, String payload, Instant clientAt) {
    return "{\"commandId\":\"" + id + "\",\"kind\":\"" + kind + "\",\"expectedVersion\":" + version
        + ",\"payload\":" + payload + ",\"clientRecordedAt\":\"" + clientAt + "\"}";
  }

  private String command(String who, String body, int expected) throws Exception {
    MvcResult r =
        http.perform(post("/api/commands").cookie(session(who)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String sync(String who, String body) throws Exception {
    MvcResult r =
        http.perform(post("/api/sync").cookie(session(who)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String read(String who, String path, int expected) throws Exception {
    MvcResult r = http.perform(get(path).cookie(session(who))).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private String upload(String who, UUID deliveryId, UUID attachmentId, String kind, byte[] content, int expected)
      throws Exception {
    MvcResult r =
        http.perform(
                put("/api/execution/deliveries/" + deliveryId + "/attachments/" + attachmentId + "?kind=" + kind)
                    .cookie(session(who))
                    .contentType(MediaType.IMAGE_PNG)
                    .content(content))
            .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  /** A fresh sign-in each time: sessions end by the clock the test keeps moving. */
  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private JsonNode json(String body) throws Exception {
    return mapper.readTree(body);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new java.util.ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }

  private int outbox(String type, UUID deliveryId) {
    return database
        .asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.query(
                "SELECT 1 FROM integration.outbox_events WHERE aggregate_id = ? AND event_type = ?",
                deliveryId.toString(), type))
        .size();
  }

  private JsonNode payload(String type, UUID deliveryId) throws Exception {
    return payloadFor(type, deliveryId.toString());
  }

  private JsonNode payloadFor(String type, String aggregateId) throws Exception {
    Map<String, Object> row =
        database.asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.queryOne(
                "SELECT payload::text AS payload FROM integration.outbox_events"
                    + " WHERE aggregate_id = ? AND event_type = ? ORDER BY seq DESC LIMIT 1",
                aggregateId, type));
    assertTrue(row != null, "no " + type + " for " + aggregateId);
    return json((String) row.get("payload"));
  }

  private Map<String, Object> row(UUID deliveryId) {
    return database.asSystem(
        ModuleRole.EXECUTION,
        () -> database.queryOne("SELECT * FROM execution.delivery_records WHERE delivery_id = ?", deliveryId));
  }

  private long count(String sql, Object... params) {
    return ((Number) database.asSystemSeparately(ModuleRole.EXECUTION, () -> database.queryOne(sql, params)).get("n"))
        .longValue();
  }

  private boolean audited(String action, String decision, String resource) {
    return !database
        .asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.query(
                "SELECT 1 FROM integration.audit_log WHERE action = ? AND decision = ? AND resource = ?",
                action, decision, resource))
        .isEmpty();
  }

  /** A PNG header followed by filler; enough for the store, which reads the type from the bytes. */
  private static byte[] png(int size) {
    byte[] out = new byte[size];
    byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(header, 0, out, 0, header.length);
    for (int i = header.length; i < size; i++) {
      out[i] = (byte) (i * 31 + size);
    }
    return out;
  }
}
