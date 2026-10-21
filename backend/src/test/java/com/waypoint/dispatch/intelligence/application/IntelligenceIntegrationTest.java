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
  @Autowired FallbackTuningJob tuningJob;
  @Autowired ModelQualityJob qualityJob;
  @Autowired LateNoticeJob lateNotices;
  @Autowired LateThresholdJob lateThresholds;
  @Autowired com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes outcomeStore;
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
  void aWeeksForecastIsJudgedAsMadeBeforeTheWeekAndDriftIsMeasuredOnPredictions() {
    // MF-03, R-ML-13. A Monday long past (2019-05-06), so these rows are never the newest forecast run.
    java.time.LocalDate monday = java.time.LocalDate.of(2019, 5, 6);
    java.time.Instant now = monday.plusDays(14).atTime(3, 50).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    java.time.Instant before = monday.minusDays(3).atStartOfDay(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    java.time.Instant late = monday.plusDays(2).atStartOfDay(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    int week = monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    String brand = reference.brandCodes().get(0);
    // Made before the week: 40 m3. Made during it: 99 m3, which must not be the one judged.
    for (Object[] f : new Object[][] {{before, "40"}, {late, "99"}}) {
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.demand_forecasts
              (forecast_id, run_id, depot_code, brand_code, iso_year, iso_week, total_m3, chilled_m3, model_label,
               degraded, generated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?::numeric, 0, 'deterministic', true, ?)
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, brand, 2019, (short) week, f[1],
          java.sql.Timestamp.from((java.time.Instant) f[0])));
    }
    // Drift: a reference month of low probabilities, then a week of high ones.
    UUID plan = UUID.randomUUID();
    database.asSystem(ModuleRole.ML, () -> database.update(
        "INSERT INTO ml.plan_scorings (plan_id, depot_code, service_date, plan_version, status, reason, requested_at, scored_at)"
            + " VALUES (?, ?, ?, 1, 'degraded', 'test', ?, ?)",
        plan, depot, java.sql.Date.valueOf(monday), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)));
    java.util.Random random = new java.util.Random(4);
    java.time.LocalDate today = monday.plusDays(14);
    for (int i = 0; i < 300; i++) {
      boolean current = i >= 150;
      double p = current ? 0.4 + 0.5 * random.nextDouble() : 0.05 + 0.3 * random.nextDouble();
      java.time.Instant at = (current ? today.minusDays(3) : today.minusDays(20))
          .atStartOfDay(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant().plusSeconds(i);
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_predictions
              (prediction_id, plan_id, order_id, trip_id, stop_sequence, outlet_id, depot_code, model_label,
               service_min, late_prob, degraded, generated_at)
          VALUES (?, ?, ?, ?, 1, 'OUT001', ?, 'deterministic', 12, ?, true, ?)
          """,
          UUID.randomUUID(), plan, UUID.randomUUID(), UUID.randomUUID(), depot,
          java.math.BigDecimal.valueOf(p).setScale(4, java.math.RoundingMode.HALF_UP), java.sql.Timestamp.from(at)));
    }

    ModelQualityJob.Measured m = qualityJob.runAt(now);

    Map<String, Object> judged = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT forecast_total_m3, actual_total_m3, baseline_total_m3 FROM ml.forecast_accuracy"
            + " WHERE depot_code = ? AND brand_code = ? AND iso_year = 2019 AND iso_week = ?", depot, brand, (short) week));
    assertTrue(judged.get("baseline_total_m3") == null, "T2-03: no orders in the 8 weeks before, so no baseline");
    assertEquals(0, new java.math.BigDecimal("40").compareTo((java.math.BigDecimal) judged.get("forecast_total_m3")),
        "the forecast made before the week, not the later one");
    assertTrue(m.psi().get(depot + "|deterministic") > 0.25, "drift seen: " + m.psi());
  }

  @Test
  void theFallbackRelearnsFromTheStopsItScoredAndStaysTheRuleWithoutThem() {
    // R-ML-14: a fallback that says 0.2 everywhere, on stops that ran late half the time.
    java.time.LocalDate day = java.time.LocalDate.of(2032, 3, 10);
    java.time.Instant now = day.plusDays(1).atTime(3, 40).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning before =
        database.asSystem(ModuleRole.ML, outcomeStore::currentTuning);
    java.util.Random random = new java.util.Random(9);
    for (int i = 0; i < 300; i++) {
      double p = 0.1 + 0.3 * random.nextDouble();
      boolean late = random.nextDouble() < 0.5;
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, predicted_late_prob, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, 1, 'delivered', ?, 'device', ?, 'deterministic', now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(day), late, p));
    }
    var fitted = tuningJob.runAt(now);
    assertTrue(fitted.labels() >= 300, "fitted on the labelled stops: " + fitted.labels());
    var current = database.asSystem(ModuleRole.ML, outcomeStore::currentTuning);
    assertEquals(fitted.labels(), current.labels());
    assertTrue(current.apply(0.2, "OUT-NEW") > 0.35, "the rule said 0.2; the stops were late half the time");
    assertTrue(before.labels() == 0 || before.labels() > 0, "read before any fit without failing");

    // Nothing to learn from in an empty window: nothing written, the newest fit stays.
    var empty = tuningJob.runAt(java.time.LocalDate.of(2040, 1, 1).atStartOfDay(
        com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant());
    assertEquals(0, empty.labels());
    assertEquals(fitted.labels(), database.asSystem(ModuleRole.ML, outcomeStore::currentTuning).labels());
  }

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
    assertEquals("2026-06-28", body.get("roadConditionsUntil").asText(), "MF-07: the last day road conditions are known");

    JsonNode sent = STUB.lastRiskRequest.get();
    JsonNode leg = sent.at("/routes/0/stops/0");
    assertEquals("DEPOT", leg.get("fromPoint").asText());
    assertTrue(sent.at("/reference/traffic_speed").size() > 0, "traffic speed is sent with the request (D9)");
    assertTrue(sent.at("/reference/calendar").size() > 365, "the calendar runs past the day for festival ramps");
  }

  @Test
  void eachStopsPredictionSaysWhyItIsAtRiskInPlainReasons() throws Exception {
    // MF-08, R-ML-15: a store late on 3 of its last 4 labelled visits is a reason on its stop.
    String label = activate("delivery_risk");
    STUB.riskLabel.set(label);
    UUID planId = publishedPlan();
    JsonNode plan = read(dispatcher, "/api/plans/" + planId, 200);
    java.time.LocalDate day = java.time.LocalDate.parse(plan.get("serviceDate").asText());
    String outlet = plan.at("/trips/0/stops/0/outletId").asText();
    for (int i = 0; i < 4; i++) {
      boolean late = i < 3;
      java.time.LocalDate visit = day.minusDays(7L * (i + 1));
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, recorded_at)
          VALUES (?, ?, ?, ?, ?, 1, 'delivered', ?, 'device', now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), outlet, depot, java.sql.Date.valueOf(visit), late));
    }
    scoreUntilDone(planId);

    JsonNode body = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200);
    boolean found = false;
    for (JsonNode s : body.get("stops")) {
      assertTrue(s.get("reasons").isArray(), "every stop carries its reasons, empty or not");
      assertTrue(s.get("reasons").size() <= 3);
      if (s.get("outletId").asText().equals(outlet)) {
        for (JsonNode r : s.get("reasons")) {
          if (r.get("code").asText().equals("OUTLET_HISTORY")) {
            assertEquals(3, r.get("value").asInt());
            assertEquals(4, r.get("of").asInt());
            found = true;
          }
        }
      }
    }
    assertTrue(found, "the store's late record is a reason: " + body.get("stops"));
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
    Object kept = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n, max(recorded_at) AS at FROM ml.scoring_inputs WHERE plan_id = ?", planId));

    database.asSystem(ModuleRole.ML, () -> database.update(
        "UPDATE ml.plan_scorings SET next_attempt_at = now() - interval '1 minute' WHERE plan_id = ?", planId));
    for (int i = 0; i < 20 && !"scored".equals(scoringRow(planId).get("status")); i++) {
      scoring.runAt(Instant.now().plusSeconds(1));
    }
    // R-ML-18: the rescoring keeps the routes as first scored.
    assertEquals(kept, database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n, max(recorded_at) AS at FROM ml.scoring_inputs WHERE plan_id = ?", planId)));

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

    // The demand model schedules each outlet's orders, so every depot's outlets go with the request.
    java.util.Set<String> sent = new java.util.HashSet<>();
    for (JsonNode o : STUB.lastForecastRequest.get().path("outlets")) {
      sent.add(o.path("depot").asText() + "|" + o.path("outlet_id").asText());
    }
    java.util.Set<String> expected = new java.util.HashSet<>();
    for (String d : reference.depotCodes()) {
      reference.outletsOfDepot(d, null).forEach(o -> expected.add(d + "|" + o.outletId()));
    }
    assertEquals(expected, sent);
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
  void eachBrandsWeeklyForecastComesWithARangeFromPooledFinishedWeeks() throws Exception {
    // MF-06, R-ML-20: 40 finished weeks of another series, ordered 0.9 to 1.1 times the forecast.
    java.time.LocalDate monday = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    String series = "RANGE-" + run;
    for (int i = 0; i < 40; i++) {
      java.time.LocalDate m = monday.minusWeeks(1 + i % 10);
      double ordered = 100 * (0.9 + 0.2 * i / 39);
      String brand = "B" + (i / 10);
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.forecast_accuracy
              (depot_code, brand_code, iso_year, iso_week, model_label, forecast_generated_at, forecast_total_m3,
               actual_total_m3, forecast_chilled_m3, actual_chilled_m3, computed_at)
          VALUES (?, ?, ?, ?, 'deterministic', now(), 100, ?, 0, 0, now())
          ON CONFLICT (depot_code, brand_code, iso_year, iso_week) DO NOTHING
          """,
          series, brand, m.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
          (short) m.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR), BigDecimal.valueOf(ordered)));
    }
    String label = activate("demand_forecast");
    STUB.demandLabel.set(label);
    forecasts.runAt(Instant.now());

    JsonNode o = read(dispatcher, "/api/ml/forecast/overview?depot=" + depot + "&weeks=2", 200);
    for (JsonNode w : o.get("weeks")) {
      for (JsonNode b : w.get("brands")) {
        double total = b.get("totalM3").asDouble();
        assertTrue(b.get("lowM3").asDouble() <= total && total <= b.get("highM3").asDouble(), b.toString());
        if (total > 0) {
          assertTrue(b.get("highM3").asDouble() > b.get("lowM3").asDouble(), b.toString());
        }
      }
    }
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
  void aModelIsBacktestedOnWhatItPredictedAndJudgedAgainstTheEstimate() throws Exception {
    // MF-04, R-ML-16: 250 stops each, two weeks ago. The estimate says 0.5 everywhere;
    // the candidate is sharp. 20 of the candidate's stops have no arrival.
    String sharp = "bt-sharp-" + run;
    java.time.LocalDate monday = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(2);
    for (int i = 0; i < 500; i++) {
      boolean candidate = i % 2 == 0;
      Boolean late = candidate && i < 40 ? null : (i / 2) % 3 == 0;
      double p = candidate ? (late != null && late ? 0.95 : 0.05) : 0.5;
      java.time.LocalDate day = monday.plusDays(i % 5);
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, predicted_late_prob, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, 1, 'delivered', ?, ?, ?, ?, now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(day), late,
          late == null ? "none" : "device", p, candidate ? sharp : "deterministic"));
    }
    JsonNode b = read(admin, "/api/ml/models/backtest?weeks=8", 200);
    assertEquals(200, b.get("minLabels").asInt());
    JsonNode m = null;
    for (JsonNode x : b.get("models")) {
      if (x.get("modelLabel").asText().equals(sharp)) {
        m = x;
      }
    }
    assertTrue(m != null, b.toString());
    assertEquals(250, m.get("stops").asInt());
    assertEquals(230, m.get("labelled").asInt(), "a stop with no arrival is not scored");
    assertEquals(0.92, m.get("coverage").asDouble(), 1e-9);
    assertEquals("BETTER", m.get("verdict").asText());
    assertEquals(1, m.get("weeks").size());

    read(admin, "/api/ml/models/backtest?weeks=13", 422);
    MvcResult outside = http.perform(get("/api/ml/models/backtest").cookie(stranger)).andReturn();
    if (outside.getResponse().getStatus() == 200) {
      assertTrue(!outside.getResponse().getContentAsString().contains(sharp), "another depot's outcomes are not read");
    } else {
      assertEquals(403, outside.getResponse().getStatus());
    }
  }

  @Test
  void theForecastScreenShowsHowTheLastFinishedWeeksDid() throws Exception {
    // T2-03: two finished weeks, judged as the nightly job keeps them.
    java.time.LocalDate monday = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    // T2-03: the 8-week mean beside each, missing for Style (too little history).
    Object[][] rows = {{monday.minusWeeks(2), "Fresh", "110", "100", "80"}, {monday.minusWeeks(2), "Style", "45", "50", null},
        {monday.minusWeeks(1), "Fresh", "90", "100", "130"}};
    for (Object[] r : rows) {
      java.time.LocalDate m = (java.time.LocalDate) r[0];
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.forecast_accuracy
              (depot_code, brand_code, iso_year, iso_week, model_label, forecast_generated_at, forecast_total_m3,
               actual_total_m3, forecast_chilled_m3, actual_chilled_m3, computed_at)
          VALUES (?, ?, ?, ?, 'deterministic', now(), ?::numeric, ?::numeric, 0, 0, now())
          ON CONFLICT (depot_code, brand_code, iso_year, iso_week) DO UPDATE SET
              forecast_total_m3 = EXCLUDED.forecast_total_m3, actual_total_m3 = EXCLUDED.actual_total_m3
          """,
          depot, r[1], m.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
          (short) m.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR), r[2], r[3]));
      database.asSystem(ModuleRole.ML, () -> database.update(
          "UPDATE ml.forecast_accuracy SET baseline_total_m3 = ?::numeric"
              + " WHERE depot_code = ? AND brand_code = ? AND iso_year = ? AND iso_week = ?",
          r[4], depot, r[1], m.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
          (short) m.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
    }
    JsonNode a = read(dispatcher, "/api/ml/forecast/accuracy?depot=" + depot + "&weeks=4", 200);
    assertEquals(2, a.get("weeks").size());
    JsonNode first = a.get("weeks").get(0);
    assertEquals(155.0, first.get("forecastM3").asDouble(), 1e-9);
    assertEquals(150.0, first.get("orderedM3").asDouble(), 1e-9);
    assertEquals(2, first.get("brands").size());
    // (10 + 5 + 10) / (100 + 50 + 100) over the two weeks.
    assertEquals(0.1, a.get("wape").asDouble(), 1e-4);
    // On the Fresh weeks that have a baseline: forecast (10 + 10) / 200, 8-week mean (20 + 30) / 200.
    assertEquals(0.1, a.get("comparedWape").asDouble(), 1e-4);
    assertEquals(0.25, a.get("baselineWape").asDouble(), 1e-4);
    assertEquals(0.2, first.get("baselineWape").asDouble(), 1e-4);

    UUID strangerId = accounts.createAccount("mfa-" + run + "@intelligence.test", "mfa", PASSWORD, "dispatcher");
    read(session("mfa-" + run), "/api/ml/forecast/accuracy?depot=" + depot, 403);
    assertTrue(strangerId != null);
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
  void aWeeksKpisAreRecordedBesideWhatTheAppCountedAndOnlyInScope() throws Exception {
    // Roadmap B-05: a week twenty weeks back, which no other test writes stops into.
    java.time.LocalDate monday = com.waypoint.dispatch.intelligence.domain.KpiBaseline.weekOf(
        java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)).minusWeeks(20);
    java.time.LocalDate wednesday = monday.plusDays(2);
    outcome(wednesday, "delivered", 0, "06:30", "device");
    outcome(wednesday, "delivered", 15, "08:30", "device");
    outcome(wednesday, "delivered", 0, "07:00", "none");
    outcome(wednesday, "failed", null, null, "device");

    String week = "{\"depotCode\":\"" + depot + "\",\"weekStart\":\"" + monday + "\",\"note\":\"From the paper run sheets\","
        + "\"values\":{\"calls_per_trip\":4.5,\"write_off_lkr\":12000}}";
    send(dispatcher, envelope("ml:RecordKpiWeek", null, week), 200);
    // A correction is a newer figure; the first stays on record.
    send(dispatcher, envelope("ml:RecordKpiWeek", null, week.replace("4.5", "3.5")), 200);
    // A share above 100 and a week not starting on a Monday are refused.
    send(dispatcher, envelope("ml:RecordKpiWeek", null, week.replace("\"calls_per_trip\":4.5", "\"adoption_pct\":120")), 422);
    send(dispatcher, envelope("ml:RecordKpiWeek", null, week.replace(monday.toString(), wednesday.toString())), 422);

    JsonNode report = read(dispatcher, "/api/ml/kpis?depot=" + depot + "&weeks=26", 200);
    JsonNode found = null;
    for (JsonNode w : report.get("weeks")) if (w.get("weekStart").asText().equals(monday.toString())) found = w;
    assertTrue(found != null, "the week is in the report");
    assertEquals(0, new java.math.BigDecimal("3.50").compareTo(found.get("recorded").get("calls_per_trip").get("value").decimalValue()));
    assertEquals("From the paper run sheets", found.get("recorded").get("calls_per_trip").get("note").asText());
    assertFalse(found.get("recorded").get("calls_per_trip").get("enteredBy").isNull(), "who entered it is recorded (rule 8)");
    JsonNode counted = found.get("counted");
    assertEquals("66.7", counted.get("fresh_on_time_pct").get("value").asText());
    assertEquals(3, counted.get("fresh_on_time_pct").get("stops").asInt());
    assertEquals("66.7", counted.get("fresh_before_8_pct").get("value").asText());
    assertEquals("75.0", counted.get("adoption_pct").get("value").asText());
    long history = ((Number) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.kpi_entries WHERE depot_code = ? AND week_start = ? AND kpi = 'calls_per_trip'",
        depot, java.sql.Date.valueOf(monday))).get("n")).longValue();
    assertEquals(2, history, "both figures are kept");

    // A depot outside the dispatcher's scope is refused and recorded, for writing and for reading.
    UUID strangerId = accounts.createAccount("mlk-" + run + "@intelligence.test", "mlk", PASSWORD, "dispatcher");
    Cookie outsider = session("mlk-" + run);
    long before = denials(strangerId, "ml:RecordKpiWeek");
    send(outsider, envelope("ml:RecordKpiWeek", null, week), 403);
    assertEquals(before + 1, denials(strangerId, "ml:RecordKpiWeek"));
    read(outsider, "/api/ml/kpis?depot=" + depot, 403);
  }

  /** A finished stop at OUT001 (Fresh) on a day, as the outcome job records it. */
  private void outcome(java.time.LocalDate day, String outcome, Integer late, String arrived, String source) {
    java.sql.Timestamp at = arrived == null ? null : java.sql.Timestamp.from(
        day.atTime(java.time.LocalTime.parse(arrived)).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant());
    database.asSystem(ModuleRole.ML, () -> database.update(
        "INSERT INTO ml.delivery_outcomes (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence,"
            + " outcome, arrived_at, late_minutes, late_label, label_source, recorded_at)"
            + " VALUES (?, ?, 'OUT001', ?, ?, 1, ?, ?, ?, ?, ?, now())",
        UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(day), outcome, at, late,
        "none".equals(source) ? null : Boolean.valueOf(late != null && late > 0), source));
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
  void aLikelyLateStopIsToldOnceToWhoTheFlagHasEarned() throws Exception {
    // T1-02, R-ML-22: the model says every stop is likely late; the job runs the evening before.
    String label = activate("delivery_risk");
    STUB.riskLabel.set(label);
    STUB.late = 0.8;
    UUID planId = publishedPlan();
    scoreUntilDone(planId);
    Instant evening = serviceDate.minusDays(1).atTime(18, 0)
        .atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
    int[] precision = database.asSystem(ModuleRole.ML, () -> database.queryOne(
            "SELECT count(*) FILTER (WHERE late_label) AS late, count(*) AS flagged FROM ml.delivery_outcomes"
                + " WHERE service_date BETWEEN ? AND ? AND late_label IS NOT NULL AND predicted_late_prob >= 0.5",
            java.sql.Date.valueOf(serviceDate.minusDays(29)), java.sql.Date.valueOf(serviceDate.minusDays(2))))
        .entrySet().stream().sorted(Map.Entry.comparingByKey()).mapToInt(e -> ((Number) e.getValue()).intValue())
        .toArray();
    // Sorted keys: flagged, late.
    boolean storesOpen = com.waypoint.dispatch.intelligence.domain.LateNoticePolicy.storesOpen(precision[1], precision[0]);

    lateNotices.runAt(evening);
    List<Map<String, Object>> events = database.asModule(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT event_id, payload::text AS payload FROM integration.outbox_events"
            + " WHERE event_type = 'stop.likely_late' AND payload->>'planId' = ?", planId.toString()));
    long stops = ((Number) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.delivery_predictions WHERE plan_id = ?", planId)).get("n")).longValue();
    assertFalse(events.isEmpty());
    assertTrue(events.size() <= stops, "once per order at most");

    // Each one reaches the audience it names.
    for (Map<String, Object> row : events) {
      var event = mapper.readValue((String) row.get("payload"),
          com.waypoint.dispatch.intelligence.contract.NoticeEvents.StopLikelyLate.class);
      assertEquals(80, event.percent());
      // The expected time is given only when it is after the window.
      event.expectedArrival().ifPresent(t -> assertTrue(t.isAfter(event.windowClose()), event.toString()));
      UUID eventId = (UUID) row.get("event_id");
      deliver("notification.on-stop-likely-late", eventId, event);
      List<Map<String, Object>> told = database.asSystem(ModuleRole.NOTIFICATION, () -> database.query(
          "SELECT recipient_user_id, title, body FROM notification.notifications WHERE event_id = ?", eventId));
      if (event.audience().equals("DISPATCHER")) {
        assertTrue(told.stream().anyMatch(n -> n.get("recipient_user_id").equals(dispatcherId)), told.toString());
        assertTrue(told.stream().allMatch(n -> ((String) n.get("title")).contains("will likely be late")), told.toString());
      } else {
        assertTrue(storesOpen, "the store is told only once the flag has earned it");
        assertTrue(told.stream().noneMatch(n -> n.get("recipient_user_id").equals(dispatcherId)));
      }
    }
    if (!storesOpen) {
      assertTrue(events.stream().allMatch(r -> ((String) r.get("payload")).contains("\"DISPATCHER\"")),
          "while the flag is learning only the dispatcher hears");
    }

    // A second run tells nobody again.
    assertEquals(0, lateNotices.runAt(evening.plusSeconds(600)));
  }

  @Test
  void aModelsLateThresholdIsFrozenOnceFromItsBacktestAndThenUsed() throws Exception {
    // MF-04, T1-02, R-ML-22: 300 labelled stops of one model in the last finished weeks, calibrated.
    String model = "freeze-" + run + "@1";
    java.time.LocalDate monday = java.time.LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    for (int i = 0; i < 300; i++) {
      double p = 0.05 + 0.9 * (i % 20) / 19.0;
      boolean late = (i / 20) < Math.round(p * 15);
      java.sql.Date day = java.sql.Date.valueOf(monday.minusDays(3 + i % 30));
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, predicted_late_prob, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, 1, 'delivered', ?, 'device', ?, ?, now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, day, late,
          BigDecimal.valueOf(p).setScale(4, java.math.RoundingMode.HALF_UP), model));
    }
    lateThresholds.runAt(Instant.now());
    Map<String, Object> frozen = database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT threshold, precision_lower, flagged FROM ml.late_thresholds WHERE model_label = ?", model));
    double threshold = ((Number) frozen.get("threshold")).doubleValue();
    assertTrue(threshold >= 0.3 && threshold <= 0.9, frozen.toString());
    assertTrue(((Number) frozen.get("precision_lower")).doubleValue() >= 0.6, frozen.toString());

    // Never moved: more stops that would choose otherwise change nothing.
    for (int i = 0; i < 100; i++) {
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               label_source, predicted_late_prob, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, 1, 'delivered', true, 'device', 0.31, ?, now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(monday.minusDays(4)), model));
    }
    lateThresholds.runAt(Instant.now());
    assertEquals(0, new BigDecimal(String.valueOf(threshold)).compareTo((BigDecimal) database.asSystem(ModuleRole.ML,
        () -> database.queryOne("SELECT threshold FROM ml.late_thresholds WHERE model_label = ?", model)).get("threshold")));
  }

  @Test
  void eachStopsUnloadingMinutesComeWithARangeFromEarlierMisses() throws Exception {
    // MF-06, R-ML-20: 40 earlier stops of the depot, before the plan's day, took 0.8 to 1.6 times the estimate.
    for (int i = 0; i < 40; i++) {
      double ratio = 0.8 + 0.8 * i / 39;
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, label_source,
               predicted_service, service_minutes, model_label, recorded_at)
          VALUES (?, ?, 'OUT-RANGE', ?, ?, 1, 'delivered', 'none', 10, ?, 'deterministic', now())
          """,
          UUID.randomUUID(), UUID.randomUUID(), depot, java.sql.Date.valueOf(serviceDate.minusDays(3)), 10 * ratio));
    }
    UUID planId = publishedPlan();
    scoreUntilDone(planId);

    JsonNode body = read(dispatcher, "/api/ml/plans/" + planId + "/predictions", 200);
    assertFalse(body.get("stops").isEmpty());
    for (JsonNode s : body.get("stops")) {
      double minutes = s.get("serviceMinutes").asDouble();
      double low = s.get("serviceLow").asDouble();
      double high = s.get("serviceHigh").asDouble();
      assertTrue(low <= minutes && minutes <= high, s.toString());
      assertTrue(high > minutes, "earlier stops ran long, so the range reaches above the estimate: " + s);
    }
    String basis = (String) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT min(service_range_basis) AS b FROM ml.delivery_predictions WHERE plan_id = ?", planId)).get("b");
    assertTrue(List.of("outlet", "depot", "all").contains(basis), basis);
  }

  @Test
  void aScoredRouteIsKeptAsTheModelReadItAndExportedWithItsOutcomes() throws Exception {
    // MF-05, R-ML-18: the built-in estimate scores the plan; its routes are kept as sent.
    UUID planId = publishedPlan();
    scoreUntilDone(planId);
    List<Map<String, Object>> predicted = database.asSystem(ModuleRole.ML, () -> database.query(
        "SELECT prediction_id, order_id, trip_id, stop_sequence FROM ml.delivery_predictions WHERE plan_id = ?"
            + " ORDER BY trip_id, stop_sequence", planId));
    assertFalse(predicted.isEmpty());
    long routes = ((Number) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.scoring_inputs WHERE plan_id = ?", planId)).get("n")).longValue();
    long trips = predicted.stream().map(r -> r.get("trip_id")).distinct().count();
    assertEquals(trips, routes, "one kept route per trip");

    // The first stop arrived late; the second has no arrival, so no label.
    for (int i = 0; i < Math.min(2, predicted.size()); i++) {
      Map<String, Object> p = predicted.get(i);
      Boolean late = i == 0 ? Boolean.TRUE : null;
      database.asSystem(ModuleRole.ML, () -> database.update(
          """
          INSERT INTO ml.delivery_outcomes
              (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, late_label,
               late_minutes, label_source, prediction_id, predicted_late_prob, model_label, recorded_at)
          VALUES (?, ?, 'OUT001', ?, ?, ?, 'delivered', ?, ?, ?, ?, 0.2, 'deterministic', now())
          """,
          UUID.randomUUID(), p.get("order_id"), depot, java.sql.Date.valueOf(serviceDate), p.get("stop_sequence"),
          late, late == null ? null : 12, late == null ? "none" : "device", p.get("prediction_id")));
    }
    String path = "/api/ml/training/routes?depot=" + depot + "&from=" + serviceDate + "&to=" + serviceDate;
    JsonNode page = read(admin, path, 200);
    JsonNode mine = null;
    for (JsonNode item : page.get("items")) {
      if (item.get("planId").asText().equals(planId.toString())) {
        mine = item;
      }
    }
    assertTrue(mine != null, page.toString());
    JsonNode stop = mine.at("/route/stops/0");
    assertEquals("DEPOT", stop.get("fromPoint").asText(), "the route as the model server reads it");
    assertTrue(stop.has("plannedArrivalTime") && stop.has("windowCloseTime"));
    JsonNode labels = mine.get("labels");
    assertTrue(labels.get(0).get("late").asBoolean());
    assertEquals("device", labels.get(0).get("labelSource").asText());
    assertEquals("deterministic", labels.get(0).get("answeredBy").asText(), "what answered then, for the replay");
    assertEquals(0.2, labels.get(0).get("answeredLateProb").asDouble(), 1e-9);
    if (labels.size() > 1) {
      assertTrue(labels.get(1).get("late").isNull(), "no arrival is no label, never not late");
    }

    read(dispatcher, path, 403);
    read(admin, "/api/ml/training/routes?depot=" + depot + "&from=2026-02-02&to=2026-02-01", 422);
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

  /** T1-01: before Publish the dispatcher keeps or swaps each stop likely to be late, with a reason. */
  @Test
  void aDraftsStopsAtRiskAreListedAndEachChoiceIsRecordedWithItsReasonInScope() throws Exception {
    demand();
    demand();
    JsonNode queued = result(send(dispatcher, envelope("plan:Generate", null,
        "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\"}"), 200));
    worker.runPending();
    UUID planId = UUID.fromString(read(dispatcher, "/api/plans/jobs/" + queued.get("jobId").asText() + "?depot=" + depot, 200)
        .get("planId").asText());
    // One stop planned at the very minute its window closes: the estimate must list it.
    database.asSystem(ModuleRole.PLANNING, () -> database.update(
        "UPDATE planning.allocations SET window_close = planned_arrival WHERE plan_id = ?"
            + " AND order_id = (SELECT order_id FROM planning.allocations WHERE plan_id = ? AND planned_arrival IS NOT NULL"
            + " ORDER BY order_id LIMIT 1)", planId, planId));

    JsonNode report = read(dispatcher, "/api/ml/plans/" + planId + "/risk-review", 200);
    assertTrue(report.get("enabled").asBoolean());
    assertTrue(report.get("estimate").asBoolean(), "labelled an estimate until the trust gates pass");
    assertTrue(report.get("stops").size() >= 1, report.toString());
    JsonNode stop = report.get("stops").get(0);
    String orderId = stop.get("orderId").asText();
    assertTrue(stop.get("percent").asInt() >= 35);
    assertTrue(stop.get("review").isNull(), "not reviewed yet");

    send(dispatcher, envelope("ml:RecordRiskReview", null,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\",\"choice\":\"KEEP\",\"reason\":\"The store receives early\"}"), 200);
    JsonNode reviewed = read(dispatcher, "/api/ml/plans/" + planId + "/risk-review", 200).get("stops").get(0);
    assertEquals("KEEP", reviewed.at("/review/choice").asText());
    assertEquals("The store receives early", reviewed.at("/review/reason").asText());

    // A change of mind is a new row; the latest is shown, both are kept.
    send(dispatcher, envelope("ml:RecordRiskReview", null,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\",\"choice\":\"SWAP\",\"reason\":\"Move it to the earlier trip\"}"), 200);
    assertEquals("SWAP", read(dispatcher, "/api/ml/plans/" + planId + "/risk-review", 200).at("/stops/0/review/choice").asText());
    assertEquals(2L, ((Number) database.asSystem(ModuleRole.ML, () -> database.queryOne(
        "SELECT count(*) AS n FROM ml.risk_reviews WHERE plan_id = ? AND order_id = ?::uuid", planId, orderId)).get("n")).longValue());

    // No reason, or a stop not at risk, is refused; another depot's dispatcher reaches neither.
    send(dispatcher, envelope("ml:RecordRiskReview", null,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\",\"choice\":\"KEEP\",\"reason\":\"\"}"), 422);
    send(dispatcher, envelope("ml:RecordRiskReview", null,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"choice\":\"KEEP\",\"reason\":\"Fine\"}"), 422);
    int strangerRead = http.perform(get("/api/ml/plans/" + planId + "/risk-review").cookie(stranger)).andReturn().getResponse().getStatus();
    assertTrue(strangerRead == 403 || strangerRead == 404, "another depot's plan is not read: " + strangerRead);
    int strangerWrite = http.perform(post("/api/commands").cookie(stranger).contentType(MediaType.APPLICATION_JSON).content(
        envelope("ml:RecordRiskReview", null, "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId
            + "\",\"choice\":\"KEEP\",\"reason\":\"Not mine\"}"))).andReturn().getResponse().getStatus();
    assertTrue(strangerWrite == 403 || strangerWrite == 404, "nor reviewed: " + strangerWrite);
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

  @Test
  void theAdministratorSeesWhereTheModelFailedAndADispatcherDoesNot() throws Exception {
    // R-ML-27: an unfaithful answer shows on the AI health screen, with its prompt version.
    UUID planId = publishedPlan();
    StubLanguage.ANSWER.set("The plan carries 9 orders.");
    explain(dispatcher, "plan:" + planId + ":health", "{\"headline\":\"2 orders\"}", 200);

    JsonNode health = read(admin, "/api/ml/ai-health?days=1", 200);
    long unfaithful = 0;
    for (JsonNode day : health.get("calls")) {
      if ("explain_plan".equals(day.get("purpose").asText())) unfaithful += day.get("unfaithful").asLong();
    }
    assertTrue(unfaithful >= 1, health.toString());
    JsonNode failure = health.get("failures").get(0);
    assertEquals("unfaithful", failure.get("outcome").asText());
    assertEquals(com.waypoint.dispatch.intelligence.domain.PromptFacts.version(AiExplainer.INSTRUCTION),
        failure.get("promptVersion").asText());
    assertTrue(health.has("jev") && health.has("suggestions"));

    read(dispatcher, "/api/ml/ai-health", 403);
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
