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
import com.waypoint.dispatch.support.TestDates;
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
@org.springframework.context.annotation.Import(IntelligenceIntegrationTest.StubLanguage.class)
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
    // The language model gateway on, answering from the stand-in below: no call leaves the test.
    registry.add("app.ai.enabled", () -> "true");
    registry.add("app.ai.groq-key", () -> "test-only");
    registry.add("app.ai.jev-enabled", () -> "true");
    registry.add("app.ai.openrouter-key", () -> "test-only");
  }

  /** A stand-in language model: records the facts it was sent and answers what the test sets. */
  @org.springframework.boot.test.context.TestConfiguration
  static class StubLanguage {
    static final java.util.concurrent.atomic.AtomicReference<String> ANSWER = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicReference<String> SENT = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<String> JEV_ANSWER = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicReference<String> JEV_STATE = new java.util.concurrent.atomic.AtomicReference<>();

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    DecisionPort stubJev() {
      return (state, instructions, options) -> {
        JEV_STATE.set(state);
        return Optional.ofNullable(JEV_ANSWER.get())
            .map(choice -> new DecisionPort.Choice(choice, 0.9, Map.of(choice, 0.9), "typesafe/jev-1.13-stub"));
      };
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    LanguageModel stubLanguageModel() {
      return new LanguageModel() {
        @Override
        public String provider() {
          return "groq";
        }

        @Override
        public String model() {
          return "stub";
        }

        @Override
        public Optional<String> complete(String instruction, String facts) {
          CALLS.incrementAndGet();
          SENT.set(facts);
          return Optional.ofNullable(ANSWER.get());
        }
      };
    }
  }

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired com.waypoint.dispatch.planning.application.PlanGenerationWorker worker;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired JdbcOrderRepository orders;
  @Autowired ConsumerInbox inbox;
  @Autowired List<EventSubscriber<?>> subscribers;
  @Autowired PlanScoringJob scoring;
  @Autowired ForecastJob forecasts;
  @Autowired OutlookWatchJob watch;
  @Autowired com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository attentionItems;
  @Autowired AttentionWatchJob attentionWatch;
  @Autowired JevShadow.Job jevJob;
  @Autowired ModelMetricsJob metricsJob;
  @Autowired com.waypoint.dispatch.planning.contract.PlanQuery planQuery;

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
    serviceDate = TestDates.unusedDay(reference::nextOperatingDay);
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
  void eachModelsDayIsMeasuredFromItsLabelledOutcomesOnly() {
    // MF-03, R-ML-13: 40 labelled stops and 5 with no arrival, on a day far from the others.
    java.time.LocalDate day = java.time.LocalDate.of(2031, 1, 10);
    java.util.Random random = new java.util.Random(3);
    for (int i = 0; i < 45; i++) {
      double p = 0.1 + 0.8 * random.nextDouble();
      Boolean late = i < 40 ? random.nextDouble() < p : null;
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, predicted_late_prob, predicted_service, service_minutes, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, 1, 'delivered', ?, ?, ?, 12, 15, 'test-model', now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(day), late, late == null ? "none" : "device",
          p));
    }
    java.time.Instant now = day.plusDays(1).atTime(3, 20).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    metricsJob.runAt(now);
    metricsJob.runAt(now);

    Map<String, Object> m = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT stops, labelled, brier, log_loss, cal_slope, service_mae, service_n FROM ml.model_metrics_daily"
            + " WHERE depot_code = ? AND service_date = ? AND model_label = 'test-model'",
        depot, java.sql.Date.valueOf(day)));
    assertEquals(45, ((Number) m.get("stops")).intValue());
    assertEquals(40, ((Number) m.get("labelled")).intValue(), "a stop with no arrival is not measured");
    double brier = ((Number) m.get("brier")).doubleValue();
    assertTrue(brier > 0 && brier < 0.35, "brier " + brier);
    assertTrue(m.get("cal_slope") != null, "fitted from 40 labelled stops");
    assertEquals(3.0, ((Number) m.get("service_mae")).doubleValue(), 1e-9);
    assertEquals(45, ((Number) m.get("service_n")).intValue());
  }

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
    var nextRun = Instant.parse(o.get("nextRunAt").asText()).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE);
    assertEquals(
        java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE),
        nextRun,
        "a model run this week means the next one is Monday's first hour (P-29)");
    for (JsonNode w : o.get("weeks")) {
      // Each operating day's own fleet, twice a day (A-40, T2-01): a vehicle another
      // test booked into the workshop is not counted on that day.
      java.time.LocalDate start = java.time.LocalDate.parse(w.get("weekStart").asText());
      BigDecimal fleetM3 = BigDecimal.ZERO;
      BigDecimal reeferM3 = BigDecimal.ZERO;
      for (var day : reference.calendarDays(start, start.plusDays(6))) {
        if (!day.operating()) continue;
        for (var v : reference.availableVehicles(depot, day.date(), null)) {
          fleetM3 = fleetM3.add(v.volumeCapM3().multiply(BigDecimal.valueOf(2)));
          if (v.refrigerated()) reeferM3 = reeferM3.add(v.volumeCapM3().multiply(BigDecimal.valueOf(2)));
        }
      }
      assertEquals(reference.brandCodes().size(), w.get("brands").size());
      assertEquals(10.5 * reference.brandCodes().size(), w.get("totalM3").asDouble(), 1e-9);
      assertEquals(2.25, w.get("chilledM3").asDouble(), 1e-9, "only Fresh has chilled demand");
      int days = w.get("operatingDays").asInt();
      assertTrue(days >= 0 && days <= 7);
      assertEquals(fleetM3.doubleValue(), w.at("/capacity/fleetM3").asDouble(), 1e-6,
          "A-40: each day's own fleet twice a day on each operating day");
      assertEquals(reeferM3.doubleValue(), w.at("/capacity/refrigeratedM3").asDouble(), 1e-6);
    }
  }

  @Test
  void aVehicleInTheWorkshopIsNotCountedOnItsWorkshopDay() throws Exception {
    // Roadmap T2-01: capacity is each day's own fleet, so a reefer booked into the
    // workshop for a day of the forecast leaves that week's refrigerated capacity.
    java.time.LocalDate monday = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    var reefer = reference.vehiclesOfDepot(depot, null).stream().filter(v -> v.refrigerated()).findFirst().orElseThrow();
    java.time.LocalDate day = reference.calendarDays(monday, monday.plusDays(6)).stream()
        .filter(c -> c.operating()).map(c -> c.date()).findFirst().orElseThrow();
    JsonNode before = read(dispatcher, "/api/ml/forecast/overview?depot=" + depot + "&weeks=1", 200).get("weeks").get(0);

    database.asModule(ModuleRole.REF, null, () -> database.update(
        "INSERT INTO ref.vehicle_day_status (vehicle_id, service_date, status, reason) VALUES (?, ?, 'in_workshop', 'service')",
        reefer.vehicleId(), java.sql.Date.valueOf(day)));

    JsonNode after = read(dispatcher, "/api/ml/forecast/overview?depot=" + depot + "&weeks=1", 200).get("weeks").get(0);
    double lost = reefer.volumeCapM3().multiply(BigDecimal.valueOf(2)).doubleValue();
    assertEquals(before.at("/capacity/refrigeratedM3").asDouble() - lost, after.at("/capacity/refrigeratedM3").asDouble(), 1e-6,
        "the reefer's two trips that day are gone from the week");
    assertEquals(before.at("/capacity/fleetM3").asDouble() - lost, after.at("/capacity/fleetM3").asDouble(), 1e-6);
    assertEquals(before.at("/capacity/refrigeratedVehicles").asInt() - 1, after.at("/capacity/refrigeratedVehicles").asInt(),
        "the week's reefers are the fewest free on any operating day");
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
  void aCapacityDecisionIsRecordedWithWhoAndWhyAndOnlyInScope() throws Exception {
    // Roadmap T2-01: the dispatcher records what they decided about a short week.
    java.time.LocalDate next = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).plusWeeks(2);
    int year = next.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
    int week = next.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    String hire = "{\"depotCode\":\"" + depot + "\",\"isoYear\":" + year + ",\"isoWeek\":" + week
        + ",\"action\":\"HIRE_VEHICLES\",\"vehicles\":2,\"refrigerated\":true,\"reason\":\"Festival week, five reefers in service\"}";
    send(dispatcher, envelope("ml:RecordCapacityDecision", null, hire), 200);

    JsonNode decisions = read(dispatcher, "/api/ml/capacity/decisions?depot=" + depot, 200);
    JsonNode d = decisions.get(0);
    assertEquals("HIRE_VEHICLES", d.get("action").asText());
    assertEquals(2, d.get("vehicles").asInt());
    assertTrue(d.get("refrigerated").asBoolean());
    assertEquals(week, d.get("isoWeek").asInt());
    assertEquals("Festival week, five reefers in service", d.get("reason").asText());
    assertFalse(d.get("decidedBy").isNull(), "who decided is recorded (rule 8)");

    // A reason is required, and hiring names how many vehicles.
    send(dispatcher, envelope("ml:RecordCapacityDecision", null, hire.replace("Festival week, five reefers in service", "")), 422);
    send(dispatcher, envelope("ml:RecordCapacityDecision", null, hire.replace(",\"vehicles\":2", "")), 422);

    // A depot outside the dispatcher's scope is refused and recorded as a denial.
    UUID strangerId = accounts.createAccount("mlc-" + run + "@intelligence.test", "mlc", PASSWORD, "dispatcher");
    Cookie outsider = session("mlc-" + run);
    long before = denials(strangerId, "ml:RecordCapacityDecision");
    send(outsider, envelope("ml:RecordCapacityDecision", null, hire), 403);
    assertEquals(before + 1, denials(strangerId, "ml:RecordCapacityDecision"));
    read(outsider, "/api/ml/capacity/decisions?depot=" + depot, 403);
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

  // ---- the date outlook a store reads (issue #224, R-ML-07) ----------------------------------

  @Test
  void aStoreReadsItsDaysOutlookFromTheDepotsTotalsWithoutAForecastItIsTooEarly() throws Exception {
    demand();
    demand();
    Cookie store = storeManager("mlo-" + run, outlet.outletId());
    LocalDate to = serviceDate.plusDays(6);

    JsonNode o = read(store, "/api/ml/outlook?outlet=" + outlet.outletId() + "&from=" + serviceDate + "&to=" + to, 200);

    assertEquals(7, o.get("days").size());
    assertFalse(o.get("forecast").asBoolean(), "no forecast reaches a day this far out");
    JsonNode first = o.get("days").get(0);
    assertEquals(serviceDate.toString(), first.get("date").asText());
    assertEquals("TOO_EARLY", first.get("status").asText(), "1.2 m3 booked is too little to call");
    assertTrue(first.get("load").asDouble() > 0, "the two booked orders count against the day");
    for (JsonNode d : o.get("days")) {
      boolean operating = reference.isOperating(LocalDate.parse(d.get("date").asText()));
      assertEquals(!operating, "CLOSED".equals(d.get("status").asText()), d.toString());
    }
    assertFalse(o.toString().contains("orderId"), "totals only, never another outlet's order");
  }

  @Test
  void anotherOutletsOutlookIsRefusedAndRecorded() throws Exception {
    String other = reference.outletsOfDepot(depot, null).stream().map(OutletView::outletId)
        .filter(id -> !id.equals(outlet.outletId())).findFirst().orElseThrow();
    UUID storeId = accounts.createAccount("mlq-" + run + "@intelligence.test", "mlq", PASSWORD, "store_manager");
    accounts.grantOutlet("mlq-" + run + "@intelligence.test", other);
    Cookie store = session("mlq-" + run);
    long before = denials(storeId, "ml:ReadOutlook");

    read(store, "/api/ml/outlook?outlet=" + outlet.outletId() + "&from=" + serviceDate + "&to=" + serviceDate, 403);

    assertEquals(before + 1, denials(storeId, "ml:ReadOutlook"));
    read(store, "/api/ml/outlook?outlet=" + other + "&from=" + serviceDate + "&to=" + serviceDate.plusDays(40), 422);
  }

  @Test
  void aBookedDayThatTurnsAtRiskWarnsTheStoreOnceAndARerunSaysNothingNew() {
    UUID booked = demandOf("0.6000");
    watch.watch(serviceDate, serviceDate, Instant.now());
    assertTrue(warnings(booked).isEmpty(), "too early to say is not a warning");

    demandOf("9999.0000"); // far more than the depot's vehicles carry that day
    watch.watch(serviceDate, serviceDate, Instant.now());
    List<Map<String, Object>> told = warnings(booked);
    assertEquals(1, told.size());
    assertEquals("AT_RISK", told.get(0).get("status"));
    assertEquals("More is expected than the vehicles can carry", told.get(0).get("reason"));

    watch.watch(serviceDate, serviceDate, Instant.now());
    assertEquals(1, warnings(booked).size(), "R-ML-08: once per order, day and status");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private UUID publishedPlan() throws Exception {
    demand();
    demand();
    JsonNode queued = result(send(dispatcher, envelope("plan:Generate", null,
        "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\"}"), 200));
    worker.runPending();
    JsonNode job = read(dispatcher, "/api/plans/jobs/" + queued.get("jobId").asText() + "?depot=" + depot, 200);
    UUID planId = UUID.fromString(job.get("planId").asText());
    send(dispatcher, envelope("plan:Publish", 1L, "{\"planId\":\"" + planId + "\"}"), 200);
    deliver("ml.on-plan-published", UUID.randomUUID(),
        new PlanPublished(planId, depot, serviceDate, 1, Optional.empty(), List.of()));
    return planId;
  }

  private void demand() {
    demandOf("0.6000");
  }

  private UUID demandOf(String volumeM3) {
    UUID id = UUID.randomUUID();
    String ref = "WPO-M" + id.toString().replace("-", "").substring(0, 11).toUpperCase();
    Order order = Order.place(id, ref, outlet.outletId(), depot, outlet.brandCode(), outlet.districtName(),
        new DeliveryDate(serviceDate, serviceDate, List.of()),
        Optional.of(new Reservation("WH-" + ref, new BigDecimal("80.000"), new BigDecimal(volumeM3), "ambient", 6)),
        List.of(new OrderLine("P-1", 6)));
    database.asSystem(ModuleRole.ORDERING,
        () -> orders.insert(order, null, Instant.now(), UUID.randomUUID(), Optional.empty()));
    return id;
  }

  private List<Map<String, Object>> warnings(UUID orderId) {
    return database.asModule(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT payload->>'status' AS status, payload->>'reason' AS reason FROM integration.outbox_events"
            + " WHERE event_type = 'order.outlook_changed' AND aggregate_id = ?", orderId.toString()));
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

  // ---- the language model gateway (roadmap AI-0, R-ML-10) ---------------------------

  @Test
  void aPlanIsWordedThroughTheGatewayWithOnlyAllowedMaskedFactsAndOnce() throws Exception {
    UUID planId = publishedPlan();
    String key = "plan:" + planId + ":1";
    String facts = "{\"headline\":\"Plan for the day\",\"carries\":\"2 orders\",\"cannotBeServed\":0,"
        + "\"notes\":[\"Call Kasun on 0771234567\"],\"driverName\":\"Dilan R.\"}";
    StubLanguage.ANSWER.set("The plan carries **2 orders**.");
    int before = StubLanguage.CALLS.get();
    JsonNode answer = explain(dispatcher, key, facts, 200);
    assertEquals("worded", answer.get("state").asText());
    assertEquals("The plan carries **2 orders**.", answer.get("text").asText());
    String sent = StubLanguage.SENT.get();
    assertTrue(sent.contains("[phone]"), "the phone number was masked: " + sent);
    assertFalse(sent.contains("0771234567"));
    assertFalse(sent.contains("driverName"), "a field off the list never leaves");

    // Asked again: served from the record, not asked twice.
    assertEquals("worded", explain(dispatcher, key, facts, 200).get("state").asText());
    assertEquals(before + 1, StubLanguage.CALLS.get());
    assertEquals("used", aiCall(key).get("outcome"));
  }

  @Test
  void anAnswerWithAnInventedFigureIsDroppedAndAnotherDepotIsRefused() throws Exception {
    UUID planId = publishedPlan();
    StubLanguage.ANSWER.set("The plan carries 7 orders.");
    JsonNode answer = explain(dispatcher, "plan:" + planId + ":2", "{\"headline\":\"2 orders\"}", 200);
    assertEquals("rules_only", answer.get("state").asText());
    assertEquals("unfaithful", aiCall("plan:" + planId + ":2").get("outcome"));
    explain(stranger, "plan:" + planId + ":3", "{\"headline\":\"x\"}", 403);
    explain(dispatcher, "anything:" + planId, "{}", 422);
  }

  private JsonNode explain(Cookie who, String key, String facts, int expected) throws Exception {
    MvcResult r = http.perform(post("/api/ml/explain").cookie(who).contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"" + key + "\",\"facts\":" + facts + "}")).andReturn();
    String body = r.getResponse().getContentAsString();
    assertEquals(expected, r.getResponse().getStatus(), body);
    return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
  }

  private Map<String, Object> aiCall(String key) {
    return database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT outcome FROM ml.ai_calls WHERE cache_key = ? ORDER BY asked_at DESC LIMIT 1", key));
  }

  // ---- Jev in shadow (roadmap AI-J1, R-ML-11) ----------------------------------------

  @Test
  void jevGuessesAPersonsIssueFromItsMaskedWordsAndTheGuessIsKeptBesideTheRealType() throws Exception {
    UUID order = demandOf("0.6000");
    JsonNode raised = mapper.readTree(send(dispatcher, envelope("issue:Raise", null,
        "{\"type\":\"ROAD_DISRUPTION\",\"severity\":\"MEDIUM\",\"depotCode\":\"" + depot + "\",\"outletId\":\""
            + outlet.outletId() + "\",\"subjects\":[{\"type\":\"order\",\"id\":\"" + order + "\"}],\"description\":\"Road flooded near the bridge, call me on 0771234567\"}"),
        200));
    UUID issueId = UUID.fromString(raised.at("/result/issueId").asText());
    com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised event = new com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised(
        issueId, com.waypoint.dispatch.issues.contract.IssueViews.IssueType.ROAD_DISRUPTION,
        com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity.MEDIUM, depot, Optional.of(outlet.outletId()), List.of());
    UUID eventId = UUID.randomUUID();
    deliver("ml.on-issue-raised-jev", eventId, event);
    deliver("ml.on-issue-raised-jev", eventId, event);

    StubLanguage.JEV_ANSWER.set("road_disruption");
    jevJob.runAt(Instant.now());
    Map<String, Object> row = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT status, jev_choice, agrees, masked_text FROM ml.jev_shadow WHERE issue_id = ?", issueId));
    assertEquals("answered", row.get("status"));
    assertEquals("road_disruption", row.get("jev_choice"));
    assertEquals(Boolean.TRUE, row.get("agrees"));
    assertTrue(((String) row.get("masked_text")).contains("[phone]"), "the phone number never left: " + row);
    assertFalse(StubLanguage.JEV_STATE.get().contains("0771234567"));
  }

  // ---- the attention watch (issue #268) ---------------------------------------------

  @Test
  void theWatchFindsAStopThatWillMissItsWindowOnAReleasedTripOfThePublishedPlan() throws Exception {
    publishedPlan();
    var plan = database.asSystem(ModuleRole.ML, () -> planQuery.publishedPlan(depot, serviceDate)).orElseThrow();
    var trip = plan.trips().get(0);
    deliver("execution.on-trip-released", UUID.randomUUID(),
        new com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased(
            trip.tripId(), plan.planId(), plan.planVersion(), trip.vehicleId(), depot, serviceDate,
            trip.stops().stream()
                .map(s -> new com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop(
                    s.sequence(), s.orderId(), s.outletId(), s.plannedArrival()))
                .toList()));
    var first = trip.stops().get(0);

    // Before the window closes on a stop not reached, nothing is critical about it yet.
    Instant late = serviceDate.atTime(first.windowClose()).plusMinutes(5)
        .atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    int open = attentionWatch.watch(depot, serviceDate, late);
    assertTrue(open >= 1, "the watch read the published plan's vehicle and its run sheet");
    JsonNode board = read(dispatcher, "/api/ml/attention?depot=" + depot + "&date=" + serviceDate, 200);
    boolean found = false;
    for (JsonNode item : board.get("items")) {
      if (item.get("outletId").asText().equals(first.outletId()) && item.get("kind").asText().equals("WINDOW_AT_RISK")) {
        found = true;
      }
    }
    assertTrue(found, "the first stop is past its window and not delivered: " + board);
    assertFalse(board.get("checkedAt").isNull(), "the watch says when it last looked");
    assertEquals(open, attentionWatch.watch(depot, serviceDate, late.plusSeconds(60)), "a second look raises nothing new");
  }

  @Test
  void anAttentionItemIsRaisedOnceRefreshedAndThenClearedWhenItNoLongerHolds() throws Exception {
    UUID delivery = UUID.randomUUID();
    UUID trip = UUID.randomUUID();
    Instant first = Instant.parse("2031-03-03T04:00:00Z");
    LocalDate day = LocalDate.of(2031, 3, 3);

    boolean raised = raiseAttention(delivery, trip, day, first);
    boolean again = raiseAttention(delivery, trip, day, first.plusSeconds(60));
    assertTrue(raised, "the first sighting raises the item");
    assertFalse(again, "the second refreshes it and raises nothing new");

    JsonNode board = read(dispatcher, "/api/ml/attention?depot=" + depot + "&date=" + day, 200);
    assertEquals(1, board.get("items").size());
    assertEquals("WINDOW_AT_RISK", board.at("/items/0/kind").asText());
    assertEquals("CRITICAL", board.at("/items/0/severity").asText());
    assertEquals(48, board.at("/items/0/minutesLeft").asInt());
    assertFalse(board.get("checkedAt").isNull(), "the watch says when it last looked");

    // The next look does not find it: it ends, and is never deleted.
    Instant later = first.plusSeconds(120);
    database.asSystem(ModuleRole.ML, () -> {
      attentionItems.clearUnseen(depot, day, later);
      attentionItems.beat(depot, 0, later);
    });
    assertEquals(0, read(dispatcher, "/api/ml/attention?depot=" + depot + "&date=" + day, 200).get("items").size());
    Map<String, Object> kept = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.attention_items WHERE delivery_id = ? AND cleared_at IS NOT NULL", delivery));
    assertEquals(1L, ((Number) kept.get("n")).longValue());
  }

  @Test
  void attentionForADepotOutsideTheReadersScopeIsRefusedNotEmpty() throws Exception {
    read(stranger, "/api/ml/attention?depot=" + depot + "&date=2031-03-04", 403);
  }

  @Test
  void aDispatcherAcknowledgesAnItemWithAReasonAndItLeavesTheList() throws Exception {
    UUID delivery = UUID.randomUUID();
    LocalDate day = LocalDate.of(2031, 3, 5);
    raiseAttention(delivery, UUID.randomUUID(), day, Instant.parse("2031-03-05T04:00:00Z"));
    String payload = "{\"depotCode\":\"" + depot + "\",\"deliveryId\":\"" + delivery
        + "\",\"kind\":\"WINDOW_AT_RISK\",\"reason\":\"The store agreed to receive late\"}";

    // A stale version is refused, and a reason is required.
    send(dispatcher, envelope("ml:AcknowledgeAttention", 9L, payload), 409);
    send(dispatcher, envelope("ml:AcknowledgeAttention", 1L, payload.replace("The store agreed to receive late", "ok")), 422);

    send(dispatcher, envelope("ml:AcknowledgeAttention", 1L, payload), 200);
    assertEquals(0, read(dispatcher, "/api/ml/attention?depot=" + depot + "&date=" + day, 200).get("items").size());
    Map<String, Object> row = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT acknowledged_by, ack_reason FROM ml.attention_items WHERE delivery_id = ?", delivery));
    assertEquals(dispatcherId, row.get("acknowledged_by"));
    assertEquals("The store agreed to receive late", row.get("ack_reason"));

    // Once acknowledged it cannot be acknowledged again.
    send(dispatcher, envelope("ml:AcknowledgeAttention", 2L, payload), 409);
  }

  @Test
  void aDispatcherWithoutTheDepotCannotAcknowledge() throws Exception {
    UUID delivery = UUID.randomUUID();
    raiseAttention(delivery, UUID.randomUUID(), LocalDate.of(2031, 3, 6), Instant.parse("2031-03-06T04:00:00Z"));
    send(stranger, envelope("ml:AcknowledgeAttention", 1L,
        "{\"depotCode\":\"" + depot + "\",\"deliveryId\":\"" + delivery
            + "\",\"kind\":\"WINDOW_AT_RISK\",\"reason\":\"Not mine to decide\"}"), 403);
  }

  private boolean raiseAttention(UUID delivery, UUID trip, LocalDate day, Instant at) {
    return database.asSystem(ModuleRole.ML, () -> {
      boolean raised = attentionItems.raise(
          delivery,
          com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionKind.WINDOW_AT_RISK,
          com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionSeverity.CRITICAL,
          depot, day, "VEH037", trip, outlet.outletId(), Optional.of(48), at);
      attentionItems.beat(depot, 1, at);
      return raised;
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

  private Cookie storeManager(String prefix, String outletId) {
    String email = prefix + "@intelligence.test";
    accounts.createAccount(email, prefix, PASSWORD, "store_manager");
    accounts.grantOutlet(email, outletId);
    return session(prefix);
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
