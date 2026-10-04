package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.StopActualView;
import com.waypoint.dispatch.intelligence.contract.ModelViews;
import com.waypoint.dispatch.intelligence.contract.PredictionViews;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator;
import com.waypoint.dispatch.intelligence.domain.ModelGate;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Decision;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Served;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.Leg;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.OrderFacts;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.Route;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.StopFacts;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.TripFacts;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.DueScoring;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.Prediction;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.Answer;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.RiskAnswer;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.StopRisk;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.platform.config.IntelligenceProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TravelView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Scores published plans: service minutes and P(late) for every stop, stored
 * with the model that produced them (R-ML-02).
 *
 * <p>Three steps, so no transaction is open while the model works (R-ML-01):
 * gather the plan and its inputs in one short read as the process, call the
 * model service with nothing open, then write every stop's prediction and the
 * plan's scoring in a transaction of its own. Every stop always gets an answer:
 * the model's when the gate allows it (R-ML-04), else the deterministic one,
 * marked degraded with the reason. A degraded plan is tried again while a model
 * is active, up to {@code app.ml.max-attempts} (P-28).
 *
 * <p>Allocation is never changed by any of this (R-ML-05): the risk is advice.
 */
@Component
public class PlanScoringJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(PlanScoringJob.class);
  private static final Duration LEASE = Duration.ofMinutes(10);
  private static final Duration HISTORY = Duration.ofDays(56);

  private final Database database;
  private final JdbcIntelligenceRepository repository;
  private final ModelServingAdapter adapter;
  private final PlanQuery plans;
  private final OrderQuery orders;
  private final ExecutionQuery execution;
  private final ReferenceQuery reference;
  private final ReferencePayload payload;
  private final IntelligenceProperties properties;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong pending = new AtomicLong();

  PlanScoringJob(
      Database database,
      JdbcIntelligenceRepository repository,
      ModelServingAdapter adapter,
      PlanQuery plans,
      OrderQuery orders,
      ExecutionQuery execution,
      ReferenceQuery reference,
      ReferencePayload payload,
      IntelligenceProperties properties,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.repository = repository;
    this.adapter = adapter;
    this.plans = plans;
    this.orders = orders;
    this.execution = execution;
    this.reference = reference;
    this.payload = payload;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
    metrics.gauge("waypoint.ml.scoring_pending", pending::get);
  }

  @Override
  public String name() {
    return "ml.plan-scoring";
  }

  @Override
  public String cron() {
    return "*/30 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many plans were scored, by a model or deterministically */
  int runAt(Instant now) {
    List<DueScoring> due =
        database.asSystem(ModuleRole.ML, () -> repository.claimDue(
            now, now.plus(LEASE), properties.scoringBatch(), properties.maxAttempts()));
    int done = 0;
    for (DueScoring d : due) {
      try {
        score(d);
        done++;
      } catch (DomainException | DataAccessException e) {
        metrics.increment("waypoint.ml.scoring_failed");
        log.warn("Scoring plan {} failed: {}", d.planId(), e.getMessage());
      }
    }
    pending.set(database.asSystem(ModuleRole.ML, repository::pendingScorings));
    return done;
  }

  /** One stop: where it is, and the deterministic answer that is always available. */
  record StopPlan(UUID orderId, UUID tripId, int sequence, String outletId, BigDecimal serviceMinutes,
      BigDecimal lateProbability) {}

  record Gathered(PlanView plan, List<StopPlan> stops, List<Route> routes, Optional<ModelRow> active) {}

  private void score(DueScoring due) {
    Optional<Gathered> gathered = database.asSystem(ModuleRole.ML, () -> gather(due.planId()));
    Instant now = clock.now();
    if (gathered.isEmpty()) {
      database.asSystem(ModuleRole.ML, () -> repository.completeScoring(
          due.planId(), due.rowVersion(), "degraded", Optional.empty(), PredictionViews.DETERMINISTIC,
          Optional.empty(), Optional.of("the plan no longer exists"), due.attempts() + 1, Optional.empty(), now));
      return;
    }
    Gathered g = gathered.get();

    Decision decision = gate(g);
    Map<String, StopRisk> byOrder = new HashMap<>();
    Optional<String> roadConditions = Optional.empty();
    Optional<String> reason = decision.reason();
    String label = PredictionViews.DETERMINISTIC;
    if (decision.useModel() && !g.routes().isEmpty()) {
      // No transaction is open here: the model may take a while (R-ML-01).
      Answer<RiskAnswer> answer = adapter.deliveryRisk(request(g));
      if (answer.value().isPresent()) {
        RiskAnswer a = answer.value().get();
        label = a.modelLabel();
        roadConditions = Optional.of("used".equals(a.roadConditions()) ? "used" : "fallback");
        a.stops().forEach(s -> byOrder.put(s.deliveryId(), s));
      } else {
        reason = Optional.of("model serving failed: " + answer.failure().orElse("unknown"));
      }
    } else if (decision.useModel()) {
      reason = Optional.of("no route of the plan could be scored by the model: order sizes are missing");
    }

    boolean scored = !byOrder.isEmpty();
    if (scored && byOrder.size() < g.stops().size()) {
      reason = Optional.of((g.stops().size() - byOrder.size())
          + " stops were estimated deterministically because their order sizes are missing");
    }
    String modelLabel = label;
    Optional<UUID> modelId = scored ? g.active().map(ModelRow::id) : Optional.empty();
    Optional<String> why = reason;
    Optional<String> road = roadConditions;
    int attempts = due.attempts() + 1;
    Optional<Instant> retry =
        !scored && g.active().isPresent() && attempts < properties.maxAttempts()
            ? Optional.of(now.plus(backoff(attempts)))
            : Optional.empty();
    database.asSystem(ModuleRole.ML, () -> {
      for (StopPlan s : g.stops()) {
        StopRisk risk = byOrder.get(s.orderId().toString());
        repository.insertPrediction(
            UuidV7.generate(now, random),
            risk != null
                ? new Prediction(g.plan().planId(), s.orderId(), s.tripId(), s.sequence(), s.outletId(),
                    g.plan().depotCode(), modelId, modelLabel, risk.serviceMinutes(), risk.lateProbability(), false)
                : new Prediction(g.plan().planId(), s.orderId(), s.tripId(), s.sequence(), s.outletId(),
                    g.plan().depotCode(), Optional.empty(), PredictionViews.DETERMINISTIC, s.serviceMinutes(),
                    s.lateProbability(), true),
            now);
      }
      repository.completeScoring(
          due.planId(), due.rowVersion(), scored ? "scored" : "degraded", modelId,
          scored ? modelLabel : PredictionViews.DETERMINISTIC, scored ? road : Optional.empty(),
          scored ? why : why.or(() -> Optional.of("no model answered")), attempts, retry, now);
    });
    metrics.increment("waypoint.ml.scoring", "outcome", scored ? "scored" : "degraded");
    if (!scored) {
      metrics.increment("waypoint.ml.fallback", "operation", "delivery_risk", "reason", category(why));
    }
  }

  private Decision gate(Gathered g) {
    Optional<String> active = g.active().map(m -> m.view().label());
    Served served;
    if (!adapter.configured() || active.isEmpty()) {
      served = Served.none();
    } else {
      Answer<Map<String, String>> loaded = adapter.loadedModels();
      served = loaded.value()
          .map(m -> Optional.ofNullable(m.get(ModelViews.DELIVERY_RISK)).map(Served::loaded).orElse(Served.none()))
          .orElseGet(() -> Served.unavailable(loaded.failure().orElse("unknown")));
    }
    return ModelGate.decide(ModelViews.DELIVERY_RISK, adapter.configured(), active, served);
  }

  /** Inside one read as the process: the plan, its stops and every input the model needs. */
  private Optional<Gathered> gather(UUID planId) {
    Optional<PlanView> found = plans.plan(planId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PlanView plan = found.get();
    UUID version = plan.referenceVersionId();
    Map<String, List<BigDecimal>> history = serviceHistory(plan.depotCode(), plan.serviceDate());
    List<StopPlan> stops = new ArrayList<>();
    List<Route> routes = new ArrayList<>();
    for (TripView trip : plan.trips()) {
      Optional<TravelView> travel = reference.travelProfile(trip.districtName(), version);
      Optional<VehicleView> vehicle = reference.vehicle(trip.vehicleId(), version);
      List<StopFacts> facts = new ArrayList<>();
      int index = 0;
      for (StopView s : trip.stops().stream().sorted(java.util.Comparator.comparingInt(StopView::sequence)).toList()) {
        DeterministicEstimator.ServiceEstimate svc =
            DeterministicEstimator.serviceMinutes(history.getOrDefault(s.outletId(), List.of()), s.serviceMinutes());
        stops.add(new StopPlan(s.orderId(), trip.tripId(), s.sequence(), s.outletId(), svc.minutes(),
            DeterministicEstimator.lateProbability(s.plannedArrival(), s.windowClose(), index++)));
        facts.add(new StopFacts(s.sequence(), s.orderId(), s.outletId(), s.plannedArrival(), s.windowOpen(),
            s.windowClose(), orders.order(s.orderId()).flatMap(PlanScoringJob::orderFacts)));
      }
      if (travel.isPresent() && vehicle.isPresent()) {
        TravelView t = travel.get();
        PlannedRoutes.route(new TripFacts(
                trip.tripId(), trip.vehicleId(), vehicle.get().vehicleType(),
                vehicle.get().refrigerated() ? "reefer" : "ambient", trip.brandCode(), trip.districtName(),
                plan.depotCode(), plan.serviceDate(), t.depotToDistrictFreeflowMin(), t.interStopFreeflowMin(),
                t.depotToDistrictKm(), t.interStopKm(), facts))
            .ifPresent(routes::add);
      }
    }
    return Optional.of(new Gathered(plan, stops, routes, repository.activeModel(ModelViews.DELIVERY_RISK)));
  }

  /**
   * The depot's recent service minutes per outlet, for the deterministic
   * estimate. Only completed stops with trusted timing count, and wait is never
   * part of service (EXE-18).
   */
  private Map<String, List<BigDecimal>> serviceHistory(String depot, LocalDate day) {
    Map<String, List<BigDecimal>> out = new HashMap<>();
    Optional<String> cursor = Optional.empty();
    LocalDate from = day.minusDays(HISTORY.toDays());
    do {
      Page<StopActualView> page = execution.actuals(depot, from, day.minusDays(1), cursor, Page.MAX_LIMIT);
      for (StopActualView a : page.items()) {
        boolean served = a.outcome() == DeliveryOutcome.DELIVERED || a.outcome() == DeliveryOutcome.PARTIAL;
        if (served && !a.timingUncertain() && a.serviceMinutes().isPresent()) {
          out.computeIfAbsent(a.outletId(), k -> new ArrayList<>()).add(a.serviceMinutes().get());
        }
      }
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  private static Optional<OrderFacts> orderFacts(OrderView o) {
    if (o.weightKg() == null || o.volumeM3() == null || o.temperature() == null || o.itemCount() <= 0
        || o.weightKg().signum() <= 0 || o.volumeM3().signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(new OrderFacts(
        o.placedAt().atZone(Clock.OPERATING_ZONE).toLocalDate(), o.deferralCount() > 0, o.temperature(),
        o.itemCount(), o.weightKg(), o.volumeM3()));
  }

  private Map<String, Object> request(Gathered g) {
    List<Map<String, Object>> routes = new ArrayList<>();
    for (Route r : g.routes()) {
      TripFacts t = r.trip();
      List<Map<String, Object>> stops = new ArrayList<>();
      for (Leg l : r.legs()) {
        stops.add(ReferencePayload.row(
            "seq", l.seq(), "deliveryId", l.orderId().toString(), "outletId", l.outletId(),
            "orderDate", l.order().orderDate().toString(), "deferred", l.order().deferred(),
            "tempRequirement", l.order().temperature(), "units", l.order().units(), "weightKg", l.order().weightKg(),
            "volumeM3", l.order().volumeM3(), "fromPoint", l.fromPoint(), "distanceKm", l.distanceKm(),
            "plannedDepartTime", ReferencePayload.time(l.plannedDepart()),
            "plannedTravelMin", l.plannedTravelMinutes(),
            "plannedArrivalTime", ReferencePayload.time(l.plannedArrival()),
            "windowOpenTime", ReferencePayload.time(l.windowOpen()),
            "windowCloseTime", ReferencePayload.time(l.windowClose())));
      }
      routes.add(ReferencePayload.row(
          "routeId", r.routeId(), "date", t.serviceDate().toString(), "depot", t.depotCode(),
          "vehicleId", t.vehicleId(), "vehicleType", t.vehicleType(), "vehicleTemp", t.vehicleTemp(),
          "brand", t.brandCode(), "district", t.districtName(), "stops", stops));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("routes", routes);
    body.put("reference", payload.forDepotDay(g.plan().depotCode(), g.plan().serviceDate(),
        g.plan().referenceVersionId()));
    return body;
  }

  /** 1, 2, 4, 8 ... minutes, capped at 30. */
  static Duration backoff(int attempts) {
    long minutes = Math.min(30, 1L << Math.min(attempts - 1, 5));
    return Duration.ofMinutes(minutes);
  }

  /** A short tag for the metric; the full reason is on the scoring row. */
  static String category(Optional<String> reason) {
    String r = reason.orElse("");
    if (r.contains("not configured")) {
      return "unconfigured";
    }
    if (r.startsWith("no ") && r.contains("model is active")) {
      return "no_active_model";
    }
    if (r.contains("but the active model is") || r.contains("has no")) {
      return "version_mismatch";
    }
    if (r.contains("rejected")) {
      return "rejected";
    }
    if (r.contains("unavailable") || r.contains("failed") || r.contains("circuit")) {
      return "unavailable";
    }
    return "other";
  }
}
