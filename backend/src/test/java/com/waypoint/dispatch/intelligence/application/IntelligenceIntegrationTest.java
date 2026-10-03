package com.waypoint.dispatch.intelligence.application;

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
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.ConsumerInbox;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Intelligence end to end against a stub model service: a real plan is
 * generated and published through the commands, its event is delivered the way
 * the relay delivers it, and the jobs are run at chosen instants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class IntelligenceIntegrationTest {
  private static final String PASSWORD = "Intelligence2026!";
  private static final StubModelServer STUB;

  static {
    try {
      STUB = new StubModelServer();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    registry.add("app.scheduling.enabled", () -> "false");
    registry.add("app.ml.base-url", STUB::baseUrl);
    // Every failure test trips it otherwise, and the next test would find it open.
    registry.add("app.ml.circuit-failures", () -> "20");
    registry.add("app.ml.circuit-open-for", () -> "1s");
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired JdbcOrderRepository orders;
  @Autowired ConsumerInbox inbox;
  @Autowired List<EventSubscriber<?>> subscribers;
  @Autowired PlanScoringJob scoring;
  @Autowired ForecastJob forecasts;

  private String run;
  private String depot;
  private OutletView outlet;
  private LocalDate serviceDate;
  private Cookie dispatcher;
  private UUID dispatcherId;
  private Cookie admin;
  private Cookie stranger;

  @BeforeEach
  void setUp() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    depot = reference.outlet("OUT001", null).orElseThrow().depotCode();
    outlet = reference.outletsOfDepot(depot, null).stream()
        .filter(o -> !o.vanOnly() && o.effectiveWindowOpen().isPresent())
        .min(Comparator.comparing(OutletView::outletId))
        .orElseThrow();
    serviceDate = reference.nextOperatingDay(
        LocalDate.of(2045, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));
    run = UUID.randomUUID().toString().substring(0, 8);
    dispatcherId = account("mld-" + run, "dispatcher", true);
    dispatcher = session("mld-" + run);
    account("mla-" + run, "admin", true);
    admin = session("mla-" + run);
    account("mls-" + run, "dispatcher", false);
    stranger = session("mls-" + run);
    STUB.reset();
  }

  @AfterEach
  void tearDown() {
    STUB.reset();
  }

  // ---- scoring a published plan ---------------------------------------------------

  @Test
  void aPublishedPlanIsScoredByTheActiveModelAndStampedWithIt() throws Exception {
    String label = activate("delivery_risk");
    STUB.riskLabel.set(label);
    UUID planId = publishedPlan();

    scoreUntilDone(planId);

    JsonNode body = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200);
    assertEquals("SCORED", body.at("/scoring/status").asText());
    assertEquals(label, body.at("/scoring/modelLabel").asText());
    assertEquals("used", body.at("/scoring/roadConditions").asText());
    assertFalse(body.get("stops").isEmpty());
    for (JsonNode s : body.get("stops")) {
      assertEquals(label, s.get("modelLabel").asText(), "R-ML-02: every prediction names its model");
      assertEquals(StubModelServer.LATE, s.get("lateProbability").asDouble(), 1e-9);
      assertFalse(s.get("degraded").asBoolean());
    }
    assertFalse(read(dispatcher, "/api/plans/" + planId, 200).get("plannedWithoutPredictor").asBoolean());

    JsonNode sent = STUB.lastRiskRequest.get();
    JsonNode leg = sent.at("/routes/0/stops/0");
    assertEquals("DEPOT", leg.get("fromPoint").asText());
    assertTrue(sent.at("/reference/traffic_speed").size() > 0, "traffic speed is sent with the request (D9)");
    assertTrue(sent.at("/reference/calendar").size() > 365, "the calendar runs past the day for festival ramps");
  }

  @Test
  void withTheModelServiceDownThePlanIsScoredDeterministicallyAndSaysSo() throws Exception {
    activate("delivery_risk");
    STUB.failing = true;
    UUID planId = publishedPlan();

    scoreUntilDone(planId);

    JsonNode body = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200);
    assertEquals("DEGRADED", body.at("/scoring/status").asText());
    assertTrue(body.at("/scoring/reason").asText().contains("unavailable"), body.at("/scoring/reason").asText());
    for (JsonNode s : body.get("stops")) {
      assertEquals("deterministic", s.get("modelLabel").asText());
      assertTrue(s.get("degraded").asBoolean());
      assertTrue(s.get("lateProbability").asDouble() > 0 && s.get("lateProbability").asDouble() < 1);
    }
    assertTrue(read(dispatcher, "/api/plans/" + planId, 200).get("plannedWithoutPredictor").asBoolean(),
        "rule 9: the plan says it was scored without the predictor");
    assertNotNull(scoringRow(planId).get("next_attempt_at"), "a model is active, so it is tried again (P-28)");
  }

  @Test
  void aDegradedPlanIsRescoredOnceTheModelIsBack() throws Exception {
    String label = activate("delivery_risk");
    STUB.riskLabel.set(label);
    STUB.failing = true;
    UUID planId = publishedPlan();
    scoreUntilDone(planId);
    STUB.failing = false;

    database.asSystem(ModuleRole.ML, () -> database.update(
        "UPDATE ml.plan_scorings SET next_attempt_at = now() - interval '1 minute' WHERE plan_id = ?", planId));
    for (int i = 0; i < 20 && !"scored".equals(scoringRow(planId).get("status")); i++) {
      scoring.runAt(Instant.now().plusSeconds(1));
    }

    JsonNode body = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200);
    assertEquals("SCORED", body.at("/scoring/status").asText());
    for (JsonNode s : body.get("stops")) {
      assertEquals(label, s.get("modelLabel").asText(), "the model's answer replaces the fallback on read");
    }
  }

  @Test
  void aServerWithAnotherVersionIsDegradedWithTheReason() throws Exception {
    String label = activate("delivery_risk");
    STUB.riskLabel.set("someone-else@9");
    UUID planId = publishedPlan();

    scoreUntilDone(planId);

    JsonNode scoring = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200).get("scoring");
    assertEquals("DEGRADED", scoring.get("status").asText());
    assertTrue(scoring.get("reason").asText().contains("someone-else@9 but the active model is " + label),
        scoring.get("reason").asText());
  }

  @Test
  void aRedeliveredPublicationQueuesOneScoring() throws Exception {
    UUID planId = publishedPlan();
    UUID eventId = UUID.randomUUID();
    PlanPublished event = new PlanPublished(planId, depot, serviceDate, 1, Optional.empty(), List.of());
    deliver("ml.on-plan-published", eventId, event);
    deliver("ml.on-plan-published", UUID.randomUUID(), event);

    long rows = ((Number) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.plan_scorings WHERE plan_id = ?", planId).get("n"))).longValue();
    assertEquals(1, rows);
  }

  @Test
  void anotherDepotsPredictionsAreNotFound() throws Exception {
    UUID planId = publishedPlan();
    scoreUntilDone(planId);
    read(stranger, "/api/ml/plans/" + planId + "/predictions", 404);
  }

  // ---- forecasts ------------------------------------------------------------------------

  @Test
  void theForecastJobStoresTenWeeksPerDepotAndBrandFromTheModel() throws Exception {
    String label = activate("demand_forecast");
    STUB.demandLabel.set(label);

    int written = forecasts.runAt(Instant.now());

    assertEquals(reference.depotCodes().size() * reference.brandCodes().size() * ForecastJob.WEEKS, written);
    LocalDate monday = LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    String week = monday.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W"
        + monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    JsonNode rows = read(dispatcher, "/api/ml/forecast?depot=" + depot + "&brand=Fresh&from=" + week + "&to=" + week, 200);
    assertEquals(1, rows.size());
    assertEquals(label, rows.get(0).get("modelVersion").asText());
    assertEquals(10.5, rows.get(0).get("totalVolumeM3").asDouble(), 1e-9);
    assertEquals(2.25, rows.get(0).get("chilledVolumeM3").asDouble(), 1e-9);
    assertFalse(rows.get(0).get("degraded").asBoolean());
  }

  @Test
  void withTheModelServiceDownTheForecastIsTheWeekdayMeanAndSaysSo() throws Exception {
    activate("demand_forecast");
    STUB.failing = true;

    forecasts.runAt(Instant.now());

    LocalDate monday = LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    String week = monday.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W"
        + monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    JsonNode rows = read(dispatcher, "/api/ml/forecast?depot=" + depot + "&brand=Fresh&from=" + week + "&to=" + week, 200);
    assertEquals("deterministic", rows.get(0).get("modelVersion").asText());
    assertTrue(rows.get(0).get("degraded").asBoolean());
  }

  @Test
  void theOverviewGivesTenWeeksWithBrandsCalendarAndFleetCapacity() throws Exception {
    String label = activate("demand_forecast");
    STUB.demandLabel.set(label);
    forecasts.runAt(Instant.now());

    JsonNode o = read(dispatcher, "/api/ml/forecast/overview?depot=" + depot + "&weeks=10", 200);

    assertEquals("READY", o.get("status").asText());
    assertEquals(label, o.get("modelLabel").asText());
    assertFalse(o.get("degraded").asBoolean());
    assertEquals(10, o.get("weeks").size());
    BigDecimal reeferM3PerDay = BigDecimal.ZERO;
    BigDecimal fleetM3PerDay = BigDecimal.ZERO;
    for (var v : reference.vehiclesOfDepot(depot, null)) {
      fleetM3PerDay = fleetM3PerDay.add(v.volumeCapM3().multiply(BigDecimal.valueOf(2)));
      if (v.refrigerated()) {
        reeferM3PerDay = reeferM3PerDay.add(v.volumeCapM3().multiply(BigDecimal.valueOf(2)));
      }
    }
    for (JsonNode w : o.get("weeks")) {
      assertEquals(reference.brandCodes().size(), w.get("brands").size());
      assertEquals(10.5 * reference.brandCodes().size(), w.get("totalM3").asDouble(), 1e-9);
      assertEquals(2.25, w.get("chilledM3").asDouble(), 1e-9, "only Fresh has chilled demand");
      int days = w.get("operatingDays").asInt();
      assertTrue(days >= 0 && days <= 7);
      assertEquals(fleetM3PerDay.multiply(BigDecimal.valueOf(days)).doubleValue(),
          w.at("/capacity/fleetM3").asDouble(), 1e-6, "A-40: the fleet twice a day on each operating day");
      assertEquals(reeferM3PerDay.multiply(BigDecimal.valueOf(days)).doubleValue(),
          w.at("/capacity/refrigeratedM3").asDouble(), 1e-6);
    }
  }

  @Test
  void aDepotOutsideScopeIsRefusedAndRecorded() throws Exception {
    UUID strangerId = accounts.createAccount("mlx-" + run + "@intelligence.test", "mlx", PASSWORD, "dispatcher");
    Cookie outsider = session("mlx-" + run);
    long before = denials(strangerId, "ml:Read");

    read(outsider, "/api/ml/forecast/overview?depot=" + depot, 403);

    assertEquals(before + 1, denials(strangerId, "ml:Read"));
  }

  @Test
  void theForecastJobRunsOnceAWeekAndCatchesUpOnAFreshDeployment() {
    STUB.failing = true;
    forecasts.runAt(Instant.now());

    assertFalse(forecasts.due(Instant.now()), "a run this week: nothing owed");
    assertTrue(forecasts.due(Instant.now().plus(java.time.Duration.ofDays(8))), "next week owes a run");
  }

  // ---- the registry ------------------------------------------------------------------------

  @Test
  void activatingAModelReturnsTheKindsPreviousOneToRegistered() throws Exception {
    String first = activate("delivery_risk");
    String second = activate("delivery_risk");

    JsonNode models = read(admin, "/api/ml/models", 200);
    assertEquals("REGISTERED", status(models, first));
    assertEquals("ACTIVE", status(models, second));
    long active = 0;
    for (JsonNode m : models) {
      active += "delivery_risk".equals(m.get("kind").asText()) && "ACTIVE".equals(m.get("status").asText()) ? 1 : 0;
    }
    assertEquals(1, active, "R-ML-03: one active model per kind");
  }

  @Test
  void aRetiredModelKeepsItsReasonAndCannotBeActivatedAgain() throws Exception {
    String name = "retire-" + run;
    register(name, "1", "delivery_risk");
    send(admin, envelope("ml:RetireModel", 1L,
        "{\"name\":\"" + name + "\",\"version\":\"1\",\"reason\":\"superseded by a retrained model\"}"), 200);

    JsonNode models = read(admin, "/api/ml/models", 200);
    assertEquals("RETIRED", status(models, name + "@1"));
    send(admin, envelope("ml:ActivateModel", 2L, "{\"name\":\"" + name + "\",\"version\":\"1\"}"), 409);
  }

  @Test
  void aStaleVersionIsRefused() throws Exception {
    String name = "stale-" + run;
    register(name, "1", "demand_forecast");
    send(admin, envelope("ml:ActivateModel", 5L, "{\"name\":\"" + name + "\",\"version\":\"1\"}"), 409);
  }

  @Test
  void aDispatcherCannotActivateAModel() throws Exception {
    String name = "denied-" + run;
    register(name, "1", "delivery_risk");
    long before = denials(dispatcherId, "ml:ActivateModel");

    send(dispatcher, envelope("ml:ActivateModel", 1L, "{\"name\":\"" + name + "\",\"version\":\"1\"}"), 403);

    assertEquals(before + 1, denials(dispatcherId, "ml:ActivateModel"));
  }

  @Test
  void anUnknownKindIsRefused() throws Exception {
    send(admin, envelope("ml:RegisterModel", null,
        "{\"name\":\"bad-" + run + "\",\"version\":\"1\",\"kind\":\"lateness\"}"), 422);
  }

  // ---- reference, training export, supply ------------------------------------------------------

  @Test
  void theReferenceImportCarriesTrafficSpeedAndRoadConditions() {
    assertFalse(reference.trafficSpeed(null).isEmpty());
    assertEquals(12, reference.roadConditions(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)).size());
    assertTrue(reference.roadConditions(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 6, 29)).isEmpty(),
        "the supplied series ends on 2026-06-28");
  }

  @Test
  void theTrainingExportIsTheAdministratorsAlone() throws Exception {
    String path = "/api/ml/training/deliveries?depot=" + depot + "&from=2026-01-01&to=2026-12-31";
    assertTrue(read(admin, path, 200).get("items").isArray());
    read(dispatcher, path, 403);
  }

  @Test
  void aPlannedOrdersSupplyProbabilityRestsOnThePlan() throws Exception {
    UUID planId = publishedPlan();
    JsonNode stops = read(dispatcher, "/api/plans/" + planId, 200).at("/trips/0/stops");
    UUID orderId = UUID.fromString(stops.get(0).get("orderId").asText());

    JsonNode supply = read(dispatcher, "/api/ml/orders/" + orderId + "/supply-probability", 200);

    assertEquals("planned", supply.get("basis").asText());
    assertEquals(0.5, supply.get("probability").asDouble(), 1e-9, "no outlet history yet: an honest half");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private UUID publishedPlan() throws Exception {
    demand();
    demand();
    JsonNode draft = result(send(dispatcher, envelope("plan:Generate", null,
        "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\"}"), 200));
    UUID planId = UUID.fromString(draft.get("planId").asText());
    send(dispatcher, envelope("plan:Publish", 1L, "{\"planId\":\"" + planId + "\"}"), 200);
    deliver("ml.on-plan-published", UUID.randomUUID(),
        new PlanPublished(planId, depot, serviceDate, 1, Optional.empty(), List.of()));
    return planId;
  }

  private void demand() {
    UUID id = UUID.randomUUID();
    String ref = "WPO-M" + id.toString().replace("-", "").substring(0, 11).toUpperCase();
    Order order = Order.place(id, ref, outlet.outletId(), depot, outlet.brandCode(), outlet.districtName(),
        new DeliveryDate(serviceDate, serviceDate, List.of()),
        Optional.of(new Reservation("WH-" + ref, new BigDecimal("80.000"), new BigDecimal("0.6000"), "ambient", 6)),
        List.of(new OrderLine("P-1", 6)));
    database.asSystem(ModuleRole.ORDERING,
        () -> orders.insert(order, null, Instant.now(), UUID.randomUUID(), Optional.empty()));
  }

  private void scoreUntilDone(UUID planId) {
    for (int i = 0; i < 20 && "pending".equals(scoringRow(planId).get("status")); i++) {
      scoring.runAt(Instant.now().plusSeconds(1));
    }
    assertFalse("pending".equals(scoringRow(planId).get("status")), "the plan was scored");
  }

  private Map<String, Object> scoringRow(UUID planId) {
    return database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT status, next_attempt_at FROM ml.plan_scorings WHERE plan_id = ?", planId));
  }

  /** Registers and activates a fresh model of the kind; returns its label. */
  private String activate(String kind) throws Exception {
    String name = kind.replace('_', '-') + "-" + run + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
    register(name, "1", kind);
    send(admin, envelope("ml:ActivateModel", 1L, "{\"name\":\"" + name + "\",\"version\":\"1\"}"), 200);
    return name + "@1";
  }

  private void register(String name, String version, String kind) throws Exception {
    send(admin, envelope("ml:RegisterModel", null,
        "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"kind\":\"" + kind
            + "\",\"trainedFrom\":\"2024-01-01\",\"trainedTo\":\"2026-02-14\",\"metrics\":{\"late_logloss\":0.1516}}"),
        200);
  }

  private static String status(JsonNode models, String label) {
    for (JsonNode m : models) {
      if ((m.get("name").asText() + "@" + m.get("version").asText()).equals(label)) {
        return m.get("status").asText();
      }
    }
    throw new AssertionError("no model " + label);
  }

  @SuppressWarnings("unchecked")
  private <E extends com.waypoint.dispatch.shared.event.DomainEvent> void deliver(String consumer, UUID eventId, E event) {
    EventSubscriber<E> subscriber = (EventSubscriber<E>) subscribers.stream()
        .filter(s -> s.consumerName().equals(consumer)).findFirst().orElseThrow();
    EventEnvelope<E> envelope =
        new EventEnvelope<>(eventId, event.type(), 1, Instant.now(), "test", Optional.empty(), Optional.empty(), event);
    database.asSystem(subscriber.moduleRole(), () -> {
      if (inbox.claim(consumer, eventId)) {
        subscriber.on(envelope);
      }
    });
  }

  private UUID account(String prefix, String role, boolean depotScope) {
    String email = prefix + "@intelligence.test";
    UUID id = accounts.createAccount(email, prefix, PASSWORD, role);
    if (depotScope) {
      accounts.grantDepot(email, depot);
    }
    return id;
  }

  private Cookie session(String prefix) {
    return new Cookie(AuthController.COOKIE, login.login(prefix + "@intelligence.test", PASSWORD, null, "127.0.0.1"));
  }

  private static String envelope(String kind, Long version, String payload) {
    return """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-10-03T08:00:00Z"}
        """.formatted(UUID.randomUUID(), kind, version == null ? "null" : version.toString(), payload);
  }

  private JsonNode result(String body) throws Exception {
    return mapper.readTree(body).get("result");
  }

  private String send(Cookie who, String body, int expected) throws Exception {
    MvcResult r = http.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
    assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return r.getResponse().getContentAsString();
  }

  private JsonNode read(Cookie who, String path, int expected) throws Exception {
    MvcResult r = http.perform(get(path).cookie(who)).andReturn();
    String body = r.getResponse().getContentAsString();
    assertEquals(expected, r.getResponse().getStatus(), body);
    return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
  }

  private long denials(UUID actorId, String action) {
    return ((Number) database.asModule(ModuleRole.INTEGRATION, null, () -> database.queryOne(
        "SELECT count(*) AS n FROM integration.audit_log WHERE actor_id = ? AND action = ? AND decision = 'DENY'",
        actorId, action).get("n"))).longValue();
  }
}
