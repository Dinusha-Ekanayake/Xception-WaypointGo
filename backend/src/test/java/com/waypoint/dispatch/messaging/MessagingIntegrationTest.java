package com.waypoint.dispatch.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueResolved;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.messaging.MessagingTestConfig.MovableClock;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
 * A trip's thread end to end (issue #136): opened by the published plan, written
 * by the dispatcher, the depot's loader, the driver and the stores on the trip,
 * each reading only what R-MSG-01 lets them, with every message under the
 * dispatcher's bell (R-NOT-14), reports made from issues (R-MSG-05) and voice
 * notes (R-MSG-06). Events go through the real outbox and relay.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MessagingTestConfig.class)
@ExtendWith(TestDatabase.class)
class MessagingIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String SECOND_OUTLET = "OUT002";
  /** Same depot, not on the trip. */
  static final String OFF_TRIP_OUTLET = "OUT003";
  static final String OTHER_DEPOT_OUTLET = "OUT076";
  static final String PASSWORD = "OnTheThread2026!";

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
  @Autowired OperatorRegistry operators;
  @Autowired com.waypoint.dispatch.messaging.application.VoiceRetentionJob voiceRetention;

  String depot;
  LocalDate day;
  String vehicleId;
  String otherVehicleId;
  String dispatcher;
  String farDispatcher;
  String loader;
  String driver;
  String otherDriver;
  String manager;
  String secondManager;
  String offTripManager;
  UUID dispatcherId;
  UUID driverId;
  UUID managerId;
  UUID secondManagerId;
  UUID loaderId;
  UUID tripId;
  UUID threadId;

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
      day = reference.nextOperatingDay(
          LocalDate.of(2040, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));
      var vehicles = reference.availableVehicles(depot, day, null);
      vehicleId = vehicles.get(0).vehicleId();
      otherVehicleId = vehicles.get(1).vehicleId();
    } while (alreadyAssigned(vehicleId, otherVehicleId, day));
    at("10:00");

    String run = UUID.randomUUID().toString().substring(0, 8);
    dispatcher = "mp-" + run + "@messaging.test";
    farDispatcher = "mf-" + run + "@messaging.test";
    loader = "ml-" + run + "@messaging.test";
    driver = "md-" + run + "@messaging.test";
    otherDriver = "mo-" + run + "@messaging.test";
    manager = "mm-" + run + "@messaging.test";
    secondManager = "ms-" + run + "@messaging.test";
    offTripManager = "mx-" + run + "@messaging.test";
    dispatcherId = accounts.createAccount(dispatcher, "Priya Dispatch", PASSWORD, "dispatcher");
    accounts.createAccount(farDispatcher, "Far Dispatch", PASSWORD, "dispatcher");
    loaderId = accounts.createAccount(loader, "Lahiru Loader", PASSWORD, "loader");
    driverId = accounts.createAccount(driver, "Dilan Driver", PASSWORD, "driver");
    UUID otherDriverId = accounts.createAccount(otherDriver, "Other Driver", PASSWORD, "driver");
    managerId = accounts.createAccount(manager, "Store One", PASSWORD, "store_manager");
    secondManagerId = accounts.createAccount(secondManager, "Store Two", PASSWORD, "store_manager");
    UUID offTripManagerId = accounts.createAccount(offTripManager, "Store Off", PASSWORD, "store_manager");
    accounts.grantDepot(dispatcher, depot);
    accounts.grantDepot(loader, depot);
    // A driver holds a depot grant too; it must not make them a dispatcher.
    accounts.grantDepot(driver, depot);
    accounts.grantDepot(farDispatcher, reference.outlet(OTHER_DEPOT_OUTLET, null).orElseThrow().depotCode());
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          grantOutlet(managerId, OUTLET);
          grantOutlet(secondManagerId, SECOND_OUTLET);
          grantOutlet(offTripManagerId, OFF_TRIP_OUTLET);
          assign(driverId, vehicleId);
          assign(otherDriverId, otherVehicleId);
        });

    tripId = UUID.randomUUID();
    publish(
        ModuleRole.PLANNING,
        new PlanPublished(UUID.randomUUID(), depot, day, 1, Optional.empty(), List.of(trip(OUTLET, SECOND_OUTLET))));
    drain();
    JsonNode thread = json(read(dispatcher, "/api/threads/by-subject?type=trip&id=" + tripId, 200));
    threadId = UUID.fromString(thread.get("threadId").asText());
  }

  @AfterEach
  void tearDown() {
    clock.reset();
  }

  @Test
  void aPublishedPlanOpensTheTripsThreadForEveryoneOnIt() throws Exception {
    JsonNode t = json(read(dispatcher, "/api/threads/" + threadId, 200));
    assertEquals("dispatcher", t.get("memberRole").asText());
    assertEquals(List.of(OUTLET, SECOND_OUTLET), strings(t.get("outletIds")));
    assertTrue(t.get("open").asBoolean());
    assertEquals("loader", json(read(loader, "/api/threads/" + threadId, 200)).get("memberRole").asText());
    assertEquals("driver", json(read(driver, "/api/threads/" + threadId, 200)).get("memberRole").asText(),
        "a driver's depot grant does not make them the dispatcher");
    JsonNode store = json(read(manager, "/api/threads/" + threadId, 200));
    assertEquals("store_manager", store.get("memberRole").asText());
    assertEquals(List.of(OUTLET), strings(store.get("myOutlets")));

    // A revision that adds a stop widens the thread; it never narrows.
    publish(
        ModuleRole.PLANNING,
        new PlanRevised(
            UUID.randomUUID(), depot, day, 2, UUID.randomUUID(), "added a stop",
            List.of(trip(OUTLET, OFF_TRIP_OUTLET)), Optional.empty(), Optional.empty()));
    drain();
    assertEquals(
        List.of(OUTLET, SECOND_OUTLET, OFF_TRIP_OUTLET).stream().sorted().toList(),
        strings(json(read(dispatcher, "/api/threads/" + threadId, 200)).get("outletIds")));
  }

  @Test
  void theDispatcherReadsEveryMessageAndEveryoneElseOnlyWhatIsForThem() throws Exception {
    say(dispatcher, "{\"to\":\"driver\",\"body\":\"Take the A1 today\"}", 200);
    say(dispatcher, "{\"to\":\"outlet\",\"outletId\":\"" + OUTLET + "\",\"body\":\"Running 15 min late\"}", 200);
    say(dispatcher, "{\"to\":\"all\",\"body\":\"Rain on the coast road\"}", 200);
    say(dispatcher, "{\"to\":\"loader\",\"body\":\"Load the reefer first\"}", 200);
    say(manager, "{\"to\":\"dispatch\",\"body\":\"We open at 06:00\"}", 200);
    say(driver, "{\"to\":\"dispatch\",\"body\":\"Leaving now\"}", 200);
    say(loader, "{\"to\":\"dispatch\",\"report\":\"loading_shortfall\",\"body\":\"2 units missing\"}", 200);

    assertEquals(7, bodies(dispatcher).size(), "the dispatcher reads everything");
    assertEquals(
        List.of("Leaving now", "Rain on the coast road", "Take the A1 today"), sorted(bodies(driver)),
        "the driver: addressed to them, for everyone, their own");
    assertEquals(
        List.of("Rain on the coast road", "Running 15 min late", "We open at 06:00"), sorted(bodies(manager)),
        "a store: to its outlet, for everyone, its own; never the crew's lane or another store's");
    assertEquals(List.of("Rain on the coast road"), bodies(secondManager));
    assertEquals(
        List.of("2 units missing", "Load the reefer first", "Rain on the coast road"), sorted(bodies(loader)),
        "a report goes to the dispatcher alone; its author still sees it");
  }

  @Test
  void whoMayWriteToWhomAndWhoMayNotReadAtAll() throws Exception {
    say(manager, "{\"to\":\"all\",\"body\":\"Hello everyone\"}", 422);
    say(driver, "{\"to\":\"all\",\"body\":\"Hello everyone\"}", 422);
    say(loader, "{\"to\":\"driver\",\"body\":\"Hi\"}", 422);
    say(dispatcher, "{\"to\":\"outlet\",\"outletId\":\"" + OFF_TRIP_OUTLET + "\",\"body\":\"Hi\"}", 422);
    say(driver, "{\"to\":\"dispatch\",\"report\":\"vehicle_fault\",\"body\":\"Brakes\"}", 200);

    say(otherDriver, "{\"to\":\"dispatch\",\"body\":\"Not my trip\"}", 403);
    assertTrue(audited("message:Post", "wpt:message:thread:" + threadId), "a refusal is audited");
    read(farDispatcher, "/api/threads/" + threadId, 403);
    read(offTripManager, "/api/threads/" + threadId + "/messages", 403);
    read(farDispatcher, "/api/threads/reports?depot=" + depot + "&date=" + day, 403);
    read(driver, "/api/threads/reports?depot=" + depot + "&date=" + day, 403);

    // R-MSG-04: read only once the day after the trip is over.
    clock.set(day.plusDays(2).atTime(LocalTime.of(9, 0)).atZone(Clock.OPERATING_ZONE).toInstant());
    say(driver, "{\"to\":\"dispatch\",\"body\":\"Late reply\"}", 409);
  }

  @Test
  void aResendFromAPhoneQueueIsOneMessage() throws Exception {
    UUID clientId = UUID.randomUUID();
    String body = "{\"to\":\"dispatch\",\"body\":\"At the gate\",\"clientMessageId\":\"" + clientId + "\"}";
    assertFalse(json(say(driver, body, 200)).get("result").get("alreadySent").asBoolean());
    assertTrue(json(say(driver, body, 200)).get("result").get("alreadySent").asBoolean());
    assertEquals(1, bodies(dispatcher).stream().filter("At the gate"::equals).count());
  }

  @Test
  void everyMessageReachesTheDispatchersBellAndTheOthersOnlyWhatIsForThem() throws Exception {
    say(manager, "{\"to\":\"dispatch\",\"body\":\"We open at 06:00\"}", 200);
    say(dispatcher, "{\"to\":\"driver\",\"body\":\"Take the A1\"}", 200);
    say(dispatcher, "{\"to\":\"all\",\"body\":\"Rain ahead\"}", 200);
    say(driver, "{\"to\":\"outlet\",\"outletId\":\"" + SECOND_OUTLET + "\",\"body\":\"Ten minutes away\"}", 200);
    drain();

    assertEquals(2, notified(dispatcherId), "the store's reply and the driver's note: never their own messages");
    assertEquals(2, notified(driverId), "to the driver, and the broadcast");
    assertEquals(1, notified(managerId), "only the broadcast reaches the first store");
    assertEquals(2, notified(secondManagerId), "the broadcast and the driver's note to it");
    assertEquals(1, notified(loaderId), "only the broadcast reaches the loader");
    assertEquals("thread", subjectOf(managerId), "a notification opens the thread");
  }

  @Test
  void aShortfallTheLoaderReportedBecomesAWarningOnTheTripForTheDispatcherAlone() throws Exception {
    publish(
        ModuleRole.LOADING,
        new LoadingShortfall(UUID.randomUUID(), tripId, UUID.randomUUID(), depot, CheckStatus.MISSING, 2, "Crate not found"));
    drain();
    JsonNode marks = json(read(dispatcher, "/api/threads/reports?depot=" + depot + "&date=" + day, 200));
    assertEquals(1, marks.size(), marks.toString());
    assertEquals("loading_shortfall", marks.get(0).get("reportType").asText());
    assertTrue(marks.get(0).get("outletId").isNull() || marks.get(0).get("outletId").isTextual(), "about a stop when the issue names one");
    assertEquals("loader", marks.get(0).get("authorRole").asText());
    assertEquals(tripId.toString(), marks.get(0).get("tripId").asText());
    assertTrue(bodies(manager).isEmpty(), "a report is not the store's to read");

    // Delivering the same events again posts nothing more (R-MSG-05).
    database.asSystemSeparately(
        ModuleRole.INTEGRATION,
        () -> database.update(
            "UPDATE integration.outbox_events SET status = 'pending' WHERE event_type = 'issue.raised' AND occurred_at >= ?",
            Timestamp.from(clock.now().minusSeconds(3600))));
    drain();
    assertEquals(1, json(read(dispatcher, "/api/threads/reports?depot=" + depot + "&date=" + day, 200)).size());
  }

  @Test
  void aVoiceReportIsHeardByTheDispatcherAndNobodyItWasNotFor() throws Exception {
    UUID voiceId = UUID.randomUUID();
    byte[] audio = new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1, 2, 3, 4};
    upload(driver, voiceId, "audio/webm", audio, 200);
    upload(driver, voiceId, "audio/webm", audio, 200);
    upload(driver, UUID.randomUUID(), "image/png", audio, 415);
    upload(offTripManager, UUID.randomUUID(), "audio/webm", audio, 403);

    // Someone else's recording cannot be posted.
    say(manager, "{\"to\":\"dispatch\",\"body\":\"\",\"voiceNoteId\":\"" + voiceId + "\"}", 422);
    say(driver, "{\"to\":\"dispatch\",\"report\":\"road_disruption\",\"body\":\"\",\"voiceNoteId\":\"" + voiceId + "\"}", 200);

    JsonNode message = json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items").get(0);
    assertEquals(voiceId.toString(), message.get("voiceNoteId").asText());
    assertEquals(audio.length, readBytes(dispatcher, voiceId, 200).length);
    readBytes(manager, voiceId, 404);

    // Byte ranges, which Safari needs to replay a note (MSG-15).
    MvcResult part = http.perform(get("/api/threads/" + threadId + "/voice/" + voiceId)
            .cookie(session(dispatcher)).header("Range", "bytes=2-4"))
        .andReturn();
    assertEquals(206, part.getResponse().getStatus());
    assertEquals("bytes 2-4/" + audio.length, part.getResponse().getHeader("Content-Range"));
    assertEquals(3, part.getResponse().getContentAsByteArray().length);
    assertEquals("bytes", part.getResponse().getHeader("Accept-Ranges"));
    assertTrue(json(read(dispatcher, "/api/threads/reports?depot=" + depot + "&date=" + day, 200))
        .get(0).get("voice").asBoolean());
  }

  @Test
  void aVoiceNotePastItsRetentionIsClearedAndItsMessageStays() throws Exception {
    UUID voiceId = UUID.randomUUID();
    byte[] audio = new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 9, 8, 7};
    upload(driver, voiceId, "audio/webm", audio, 200);
    say(driver, "{\"to\":\"dispatch\",\"body\":\"\",\"voiceNoteId\":\"" + voiceId + "\"}", 200);
    assertEquals(audio.length, readBytes(dispatcher, voiceId, 200).length);

    // A day short of the retention nothing goes; past it the audio is cleared once.
    voiceRetention.runAt(clock.now().plus(java.time.Duration.ofDays(399)));
    assertEquals(audio.length, readBytes(dispatcher, voiceId, 200).length);
    java.time.Instant later = clock.now().plus(java.time.Duration.ofDays(401));
    assertTrue(voiceRetention.runAt(later) >= 1);
    readBytes(dispatcher, voiceId, 404);
    voiceRetention.runAt(later);

    JsonNode message = json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items").get(0);
    assertEquals(voiceId.toString(), message.get("voiceNoteId").asText(), "the message is a record and stays");
    Map<String, Object> row = database.asSystem(ModuleRole.MESSAGING, () -> database.queryOne(
        "SELECT content IS NULL AS cleared, purged_at IS NOT NULL AS purged, sha256 FROM messaging.voice_notes"
            + " WHERE voice_note_id = ?", voiceId));
    assertEquals(true, row.get("cleared"));
    assertEquals(true, row.get("purged"));
    assertTrue(String.valueOf(row.get("sha256")).length() == 64, "the checksum stays as the record");
  }

  @Test
  void aSharedLoaderDeviceWritesAsTheLoaderWhoEnteredTheirPin() throws Exception {
    String run = UUID.randomUUID().toString().substring(0, 8);
    String second = "ml2-" + run + "@messaging.test";
    UUID secondId = accounts.createAccount(second, "Kasun Second", PASSWORD, "loader");
    accounts.grantDepot(second, depot);
    operators.setPin(loader, "1357", null);
    operators.setPin(second, "2468", null);
    Cookie device = session(loader);
    MvcResult switched = http.perform(post("/api/session/operator").cookie(device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":\"" + secondId + "\",\"pin\":\"2468\"}"))
        .andReturn();
    assertEquals(200, switched.getResponse().getStatus(), switched.getResponse().getContentAsString());

    String envelope = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"message:Post\",\"expectedVersion\":null,"
        + "\"payload\":{\"threadId\":\"" + threadId + "\",\"to\":\"dispatch\",\"body\":\"Two crates short\"},"
        + "\"clientRecordedAt\":\"" + clock.now() + "\",\"actingUserId\":\"" + secondId + "\"}";
    MvcResult posted = http.perform(post("/api/commands").cookie(device).contentType(MediaType.APPLICATION_JSON)
        .content(envelope)).andReturn();
    assertEquals(200, posted.getResponse().getStatus(), posted.getResponse().getContentAsString());
    JsonNode newest = json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items").get(0);
    assertEquals("Kasun Second", newest.get("authorName").asText(), "the operator, not the device's account, wrote it");

    // Naming a loader who is not operating the device is refused.
    String stranger = envelope.replace(secondId.toString() + "\"}", loaderId + "\"}")
        .replaceFirst("\"commandId\":\"[^\"]+\"", "\"commandId\":\"" + UUID.randomUUID() + "\"");
    assertEquals(403, http.perform(post("/api/commands").cookie(device).contentType(MediaType.APPLICATION_JSON)
        .content(stranger)).andReturn().getResponse().getStatus());
  }

  @Test
  void theDispatcherResolvesAReportAndItsWarningSignLeavesTheTimeline() throws Exception {
    JsonNode posted = json(say(driver, "{\"to\":\"dispatch\",\"report\":\"vehicle_fault\",\"body\":\"Tyre warning\"}", 200));
    UUID reportId = UUID.fromString(posted.get("result").get("messageId").asText());
    JsonNode chat = json(say(driver, "{\"to\":\"dispatch\",\"body\":\"Just a note\"}", 200));
    String path = "/api/threads/reports?depot=" + depot + "&date=" + day;
    assertEquals(1, json(read(dispatcher, path, 200)).size());

    // Not a report, not theirs to resolve, not a dispatcher of the depot: refused (R-MSG-07).
    resolve(dispatcher, UUID.fromString(chat.get("result").get("messageId").asText()), "", 422);
    resolve(driver, reportId, "", 403);
    resolve(farDispatcher, reportId, "", 403);

    JsonNode done = json(resolve(dispatcher, reportId, "Spare fitted at OUT001", 200));
    assertEquals(false, done.get("result").get("alreadyResolved").asBoolean());
    assertEquals(0, json(read(dispatcher, path, 200)).size(), "the sign leaves the timeline");
    assertTrue(json(resolve(dispatcher, reportId, "again", 200)).get("result").get("alreadyResolved").asBoolean());

    JsonNode report = null;
    for (JsonNode m : json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items")) {
      if (m.get("messageId").asText().equals(reportId.toString())) report = m;
    }
    assertTrue(report != null && report.hasNonNull("resolvedAt"), "the report stays, resolved");
    assertEquals("Spare fitted at OUT001", report.get("resolutionNote").asText());
    assertEquals("Priya Dispatch", report.get("resolvedByName").asText());
  }

  @Test
  void aReportFromAnIssueIsResolvedWhenTheIssueIs() throws Exception {
    publish(
        ModuleRole.LOADING,
        new LoadingShortfall(UUID.randomUUID(), tripId, UUID.randomUUID(), depot, CheckStatus.MISSING, 1, "Crate short"));
    drain();
    String path = "/api/threads/reports?depot=" + depot + "&date=" + day;
    assertEquals(1, json(read(dispatcher, path, 200)).size());
    UUID issueId = (UUID) database.asSystem(ModuleRole.MESSAGING, () -> database.queryOne(
        "SELECT source_issue_id FROM messaging.messages WHERE thread_id = ? AND kind = 'report'", threadId))
        .get("source_issue_id");
    assertTrue(issueId != null, "the report remembers its issue");

    publish(ModuleRole.ISSUES, new IssueResolved(issueId, depot, Optional.empty(), "REDELIVERY_BOOKED", clock.now()));
    drain();
    assertEquals(0, json(read(dispatcher, path, 200)).size());
    // Delivered twice, nothing changes.
    publish(ModuleRole.ISSUES, new IssueResolved(issueId, depot, Optional.empty(), "REDELIVERY_BOOKED", clock.now()));
    drain();
    JsonNode report = json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items").get(0);
    assertEquals("Issue resolved", report.get("resolvedByName").asText());
  }

  @Test
  void aVoiceNoteKeepsItsWaveformAndItsNotificationNamesTheAudio() throws Exception {
    UUID voiceId = UUID.randomUUID();
    byte[] audio = new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 5, 6};
    MvcResult up = http.perform(
            put("/api/threads/" + threadId + "/voice/" + voiceId + "?durationMs=3000&peaks=10,55,100,250")
                .cookie(session(driver)).contentType("audio/webm").content(audio))
        .andReturn();
    assertEquals(200, up.getResponse().getStatus(), up.getResponse().getContentAsString());
    say(driver, "{\"to\":\"dispatch\",\"body\":\"\",\"voiceNoteId\":\"" + voiceId + "\"}", 200);
    drain();

    JsonNode m = json(read(dispatcher, "/api/threads/" + threadId + "/messages", 200)).get("items").get(0);
    assertEquals(List.of("10", "55", "100", "100"), strings(m.get("voicePeaks")), "clamped to 0 to 100");
    Map<String, Object> facts = database.asSystemSeparately(ModuleRole.NOTIFICATION, () -> database.queryOne(
        "SELECT facts->>'voiceNoteId' AS v, facts->>'voiceDurationMs' AS d, facts->>'voicePeaks' AS p"
            + " FROM notification.notifications WHERE recipient_user_id = ? AND event_type = 'message.posted'"
            + " ORDER BY created_at DESC LIMIT 1", dispatcherId));
    assertEquals(voiceId.toString(), facts.get("v"));
    assertEquals("3000", facts.get("d"));
    assertEquals("10,55,100,100", facts.get("p"));
  }

  @Test
  void aStoresReportIsDrawnOnItsOwnStop() throws Exception {
    say(manager, "{\"to\":\"dispatch\",\"report\":\"stock_discrepancy\",\"body\":\"Two units short\"}", 200);
    say(driver, "{\"to\":\"dispatch\",\"report\":\"vehicle_fault\",\"body\":\"Tyre warning\"}", 200);
    JsonNode marks = json(read(dispatcher, "/api/threads/reports?depot=" + depot + "&date=" + day, 200));
    assertEquals(2, marks.size());
    for (JsonNode m : marks) {
      if ("store_manager".equals(m.get("authorRole").asText())) assertEquals(OUTLET, m.get("outletId").asText());
      else assertTrue(m.get("outletId").isNull(), "a driver's report is about the whole trip");
    }
  }

  private String resolve(String who, UUID messageId, String note, int expected) throws Exception {
    String envelope =
        "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"message:Resolve\",\"expectedVersion\":null,"
            + "\"payload\":{\"messageId\":\"" + messageId + "\",\"note\":\"" + note + "\"},"
            + "\"clientRecordedAt\":\"" + clock.now() + "\"}";
    MvcResult r = http.perform(post("/api/commands").cookie(session(who)).contentType(MediaType.APPLICATION_JSON)
        .content(envelope)).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  // ---- helpers ----

  private PlannedTrip trip(String... outlets) {
    List<PlannedStop> stops = new ArrayList<>();
    for (int i = 0; i < outlets.length; i++) {
      stops.add(new PlannedStop(i + 1, UUID.randomUUID(), outlets[i], LocalTime.of(5, 20 + i * 10)));
    }
    return new PlannedTrip(tripId, vehicleId, 1, "Fresh", "Colombo", "ambient", LocalTime.of(4, 30), stops);
  }

  private String say(String who, String payload, int expected) throws Exception {
    String body = payload.replaceFirst("\\{", "{\"threadId\":\"" + threadId + "\",");
    String envelope =
        "{\"commandId\":\"" + UUID.randomUUID() + "\",\"kind\":\"message:Post\",\"expectedVersion\":null,\"payload\":"
            + body + ",\"clientRecordedAt\":\"" + clock.now() + "\"}";
    MvcResult r =
        http.perform(post("/api/commands").cookie(session(who)).contentType(MediaType.APPLICATION_JSON).content(envelope))
            .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private List<String> bodies(String who) throws Exception {
    List<String> out = new ArrayList<>();
    for (JsonNode m : json(read(who, "/api/threads/" + threadId + "/messages", 200)).get("items")) {
      out.add(m.get("body").asText());
    }
    return out;
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted().toList();
  }

  private void upload(String who, UUID voiceId, String type, byte[] content, int expected) throws Exception {
    MvcResult r =
        http.perform(
                put("/api/threads/" + threadId + "/voice/" + voiceId + "?durationMs=4000")
                    .cookie(session(who))
                    .contentType(type)
                    .content(content))
            .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
  }

  private byte[] readBytes(String who, UUID voiceId, int expected) throws Exception {
    MvcResult r = http.perform(get("/api/threads/" + threadId + "/voice/" + voiceId).cookie(session(who))).andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsByteArray();
  }

  private long notified(UUID userId) {
    return ((Number)
            database
                .asSystemSeparately(
                    ModuleRole.NOTIFICATION,
                    () -> database.queryOne(
                        "SELECT count(*) AS n FROM notification.notifications"
                            + " WHERE recipient_user_id = ? AND event_type = 'message.posted'",
                        userId))
                .get("n"))
        .longValue();
  }

  private String subjectOf(UUID userId) {
    return (String)
        database
            .asSystemSeparately(
                ModuleRole.NOTIFICATION,
                () -> database.queryOne(
                    "SELECT subject_type FROM notification.notifications"
                        + " WHERE recipient_user_id = ? AND event_type = 'message.posted' LIMIT 1",
                    userId))
            .get("subject_type");
  }

  private boolean audited(String action, String resource) {
    return database.asSystemSeparately(
            ModuleRole.INTEGRATION,
            () -> database.queryOne(
                "SELECT 1 FROM integration.audit_log WHERE action = ? AND resource = ? AND decision = 'DENY' LIMIT 1",
                action, resource))
        != null;
  }

  private boolean alreadyAssigned(String vehicle, String otherVehicle, LocalDate on) {
    return database.asSystemSeparately(
        ModuleRole.IAM,
        () -> database.queryOne(
                "SELECT 1 FROM iam.vehicle_driver_assignments WHERE vehicle_id IN (?, ?)"
                    + " AND validity && daterange(?::date, ?::date) LIMIT 1",
                vehicle, otherVehicle, Date.valueOf(on.minusDays(1)), Date.valueOf(on.plusDays(3)))
            != null);
  }

  private void assign(UUID driverUserId, String vehicle) {
    database.update(
        "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
            + " VALUES (?, ?, daterange(?::date, ?::date))",
        vehicle, driverUserId, Date.valueOf(day), Date.valueOf(day.plusDays(1)));
  }

  private void grantOutlet(UUID userId, String outlet) {
    database.update("INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)", userId, outlet);
  }

  private void at(String time) {
    clock.set(day.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant());
  }

  private void publish(ModuleRole role, DomainEvent event) {
    database.asSystem(role, () -> publisher.publish(Actor.SYSTEM, event));
  }

  private void drain() {
    for (int pass = 0; pass < 100; pass++) {
      if (relay.deliverBatch() == 0) {
        Object failures =
            database.asSystemSeparately(
                ModuleRole.INTEGRATION,
                () -> database.query(
                    "SELECT event_type, last_error FROM integration.outbox_events"
                        + " WHERE status IN ('failed','dead') AND occurred_at >= ?"
                        // The plan here names orders Ordering never took; Loading
                        // refuses it, which is Loading's rule, not this test's.
                        + " AND coalesce(last_error, '') NOT LIKE 'loading.on-plan-%'",
                    Timestamp.from(clock.now().minusSeconds(86_400 * 2))));
        assertEquals("[]", String.valueOf(failures), "an event of this test failed delivery");
        return;
      }
    }
    throw new AssertionError("the relay never ran out of work");
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

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

}
