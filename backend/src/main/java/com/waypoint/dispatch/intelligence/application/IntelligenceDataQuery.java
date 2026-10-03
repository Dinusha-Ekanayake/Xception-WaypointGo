package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.StopActualView;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelQuery;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelVersionView;
import com.waypoint.dispatch.intelligence.contract.PredictionQuery;
import com.waypoint.dispatch.intelligence.contract.PredictionViews;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.BrandVolumeView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.ForecastOverviewView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.ForecastWeekView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.OverviewStatus;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanPredictionsView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.WeekCapacityView;
import com.waypoint.dispatch.intelligence.domain.FleetCapacity;
import com.waypoint.dispatch.intelligence.domain.ForecastSchedule;
import com.waypoint.dispatch.intelligence.contract.ModelViews;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanScoringView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.ScoringStatus;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.StopPredictionView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.SupplyProbabilityView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.TrainingDeliveryView;
import com.waypoint.dispatch.intelligence.contract.TravelAndServiceEstimator;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator;
import com.waypoint.dispatch.intelligence.domain.SupplyPolicy;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reads what Intelligence has stored, and answers the estimator port, without
 * calling any model: a read never waits on the model service.
 *
 * <p>Reads run as {@code waypoint_ml} for the asking actor, so row-level
 * security limits predictions, scorings and forecasts to the actor's depots
 * (rule 7). Contract methods read as the ambient actor, web methods as the
 * authenticated one.
 */
@Component
public class IntelligenceDataQuery implements PredictionQuery, ModelQuery, TravelAndServiceEstimator {
  public static final String READ = "ml:Read";
  public static final String EXPORT = "ml:ExportTrainingData";
  private static final Set<DeliveryOutcome> FAILED = Set.of(DeliveryOutcome.FAILED, DeliveryOutcome.SKIPPED);
  private static final Set<OrderStatus> DONE =
      Set.of(OrderStatus.DELIVERED, OrderStatus.PARTIALLY_DELIVERED, OrderStatus.RECEIVED);

  private final Database database;
  private final JdbcIntelligenceRepository repository;
  private final PlanQuery plans;
  private final OrderQuery orders;
  private final ExecutionQuery execution;
  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Clock clock;
  private final com.waypoint.dispatch.platform.audit.AuditLog audit;

  public IntelligenceDataQuery(
      Database database,
      JdbcIntelligenceRepository repository,
      PlanQuery plans,
      OrderQuery orders,
      ExecutionQuery execution,
      ReferenceQuery reference,
      Metrics metrics,
      Clock clock,
      com.waypoint.dispatch.platform.audit.AuditLog audit) {
    this.database = database;
    this.repository = repository;
    this.plans = plans;
    this.orders = orders;
    this.execution = execution;
    this.reference = reference;
    this.metrics = metrics;
    this.clock = clock;
    this.audit = audit;
  }

  // ---- PredictionQuery: as the ambient actor -----------------------------------

  @Override
  public Optional<PlanScoringView> planScoring(UUID planId) {
    return read(ambient(), () -> repository.scoring(planId).map(IntelligenceDataQuery::scoring));
  }

  @Override
  public Optional<PlanPredictionsView> predictionsFor(UUID planId) {
    return read(ambient(), () -> predictions(planId));
  }

  @Override
  public List<DemandForecast> forecast(
      String depotCode, String brandCode, int fromYear, int fromWeek, int toYear, int toWeek) {
    return read(ambient(), () -> forecasts(depotCode, brandCode, fromYear * 100 + fromWeek, toYear * 100 + toWeek));
  }

  @Override
  public Optional<SupplyProbabilityView> supplyProbability(UUID orderId) {
    return metrics.time("waypoint.ml.estimate", () -> supply(orderId), "operation", "supply_probability");
  }

  // ---- ModelQuery -----------------------------------------------------------------

  @Override
  public Optional<ModelVersionView> activeModel(String kind) {
    return read(ambient(), () -> repository.activeModel(kind).map(ModelRow::view));
  }

  @Override
  public List<ModelVersionView> models() {
    return read(ambient(), () -> repository.allModels().stream().map(ModelRow::view).toList());
  }

  // ---- TravelAndServiceEstimator: the deterministic default --------------------------

  /**
   * The outlet's median actual service over eight weeks, or its allowance with
   * less history (A-16). Learned service minutes come from scoring a whole
   * route, so they live on the plan's predictions, not here.
   */
  @Override
  public ServiceTimeEstimate serviceTime(String outletId, String brandCode, String dockType, LocalDate serviceDate) {
    return metrics.time("waypoint.ml.estimate", () -> {
      BigDecimal allowance = reference.serviceAllowance(brandCode, dockType, null)
          .map(a -> a.minutes())
          .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
              "No service allowance for " + brandCode + " at a " + dockType + " dock"));
      List<BigDecimal> history = reference.outlet(outletId, null)
          .map(o -> outletHistory(o.depotCode(), outletId, serviceDate).stream()
              .filter(a -> a.outcome() == DeliveryOutcome.DELIVERED || a.outcome() == DeliveryOutcome.PARTIAL)
              .filter(a -> !a.timingUncertain())
              .flatMap(a -> a.serviceMinutes().stream())
              .toList())
          .orElse(List.of());
      var e = DeterministicEstimator.serviceMinutes(history, allowance);
      return new ServiceTimeEstimate(e.minutes(), PredictionViews.DETERMINISTIC, false);
    }, "operation", "service_time");
  }

  /** The planned stop's stored prediction when its plan was scored, else the slack heuristic. */
  @Override
  public LatenessEstimate lateness(String outletId, LocalTime plannedArrival, LocalDate serviceDate) {
    return metrics.time("waypoint.ml.estimate", () -> {
      LocalTime close = reference.outlet(outletId, null)
          .map(o -> o.effectiveWindowClose().orElse(o.windowClose()))
          .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId));
      return new LatenessEstimate(
          DeterministicEstimator.lateProbability(plannedArrival, close, 0), PredictionViews.DETERMINISTIC, false);
    }, "operation", "lateness");
  }

  @Override
  public Optional<DemandForecast> demandForecast(String depotCode, String brandCode, int isoYear, int isoWeek) {
    return forecast(depotCode, brandCode, isoYear, isoWeek, isoYear, isoWeek).stream().findFirst();
  }

  // ---- web: as the authenticated actor ------------------------------------------------

  public List<ModelVersionView> models(Actor actor) {
    return read(actor.userId(), () -> repository.allModels().stream().map(ModelRow::view).toList());
  }

  public PlanPredictionsView predictionsFor(Actor actor, UUID planId) {
    return read(actor.userId(), () -> predictions(planId))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
            "No predictions for plan " + planId + " within your scope; it may not be published yet"));
  }

  public List<DemandForecast> forecast(Actor actor, String depot, String brand, int fromKey, int toKey) {
    return read(actor.userId(), () -> forecasts(depot, brand, fromKey, toKey));
  }

  public SupplyProbabilityView supplyProbability(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> supply(orderId))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No order " + orderId + " within your scope"));
  }

  /** EXE-18: actuals with wait apart from service, for retraining. */
  public Page<TrainingDeliveryView> trainingDeliveries(
      Actor actor, String depot, LocalDate from, LocalDate to, Optional<String> cursor, Integer limit) {
    if (from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "from is after to");
    }
    Page<StopActualView> page =
        read(actor.userId(), () -> execution.actuals(depot, from, to, cursor, Page.limit(limit)));
    return new Page<>(page.items().stream().map(IntelligenceDataQuery::training).toList(), page.nextCursor());
  }

  /**
   * The Forecast screen's read for one depot: the next {@code weeks} ISO weeks
   * from next Monday, each with the newest forecast per brand, the calendar
   * around it and what the depot's fleet can carry (A-40). One call per depot
   * instead of one per brand, day and vehicle list.
   */
  public ForecastOverviewView forecastOverview(Actor actor, String depot, int weeks) {
    if (weeks < 1 || weeks > 12) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "weeks must be from 1 to 12");
    }
    requireDepot(actor, depot);
    LocalDate origin = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate()
        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    LocalDate end = origin.plusWeeks(weeks).minusDays(1);
    int fromKey = weekKey(origin);
    int toKey = weekKey(end);
    List<Map<String, Object>> rows =
        read(actor.userId(), () -> repository.latestDepotForecasts(depot, fromKey, toKey));
    List<CalendarDayView> days = reference.calendarDays(origin, end);
    List<FleetCapacity.Vehicle> fleet = reference.vehiclesOfDepot(depot, null).stream()
        .map(v -> new FleetCapacity.Vehicle(v.volumeCapM3(), v.refrigerated()))
        .toList();

    Optional<Instant> generatedAt = rows.stream()
        .map(r -> JdbcIntelligenceRepository.instant(r.get("generated_at")))
        .max(java.util.Comparator.naturalOrder());
    Optional<String> label = rows.stream().map(r -> (String) r.get("model_label")).distinct()
        .reduce((a, b) -> "mixed");
    boolean degraded = rows.stream().anyMatch(r -> (Boolean) r.get("degraded"));

    List<ForecastWeekView> out = new ArrayList<>();
    for (LocalDate monday = origin; !monday.isAfter(end); monday = monday.plusWeeks(1)) {
      LocalDate weekStart = monday;
      List<CalendarDayView> week = days.stream()
          .filter(d -> !d.date().isBefore(weekStart) && d.date().isBefore(weekStart.plusDays(7)))
          .toList();
      int year = monday.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
      int isoWeek = monday.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      List<BrandVolumeView> brands = rows.stream()
          .filter(r -> ((Number) r.get("iso_year")).intValue() == year
              && ((Number) r.get("iso_week")).intValue() == isoWeek)
          .map(r -> new BrandVolumeView(
              (String) r.get("brand_code"), (BigDecimal) r.get("total_m3"), (BigDecimal) r.get("chilled_m3")))
          .sorted(java.util.Comparator.comparing(BrandVolumeView::brandCode))
          .toList();
      int operating = (int) week.stream().filter(CalendarDayView::operating).count();
      FleetCapacity.Week capacity = FleetCapacity.weekly(fleet, operating);
      out.add(new ForecastWeekView(
          year, isoWeek, weekStart, operating,
          (int) week.stream().filter(CalendarDayView::holiday).count(),
          (int) week.stream().filter(CalendarDayView::payday).count(),
          week.stream().map(CalendarDayView::festival).filter(f -> f != null && !f.isBlank()).findFirst(),
          (int) week.stream().filter(CalendarDayView::generated).count(),
          brands,
          brands.stream().map(BrandVolumeView::totalM3).reduce(BigDecimal.ZERO, BigDecimal::add),
          brands.stream().map(BrandVolumeView::chilledM3).reduce(BigDecimal.ZERO, BigDecimal::add),
          new WeekCapacityView(capacity.vehicles(), capacity.refrigeratedVehicles(), capacity.fleetM3(),
              capacity.refrigeratedM3())));
    }
    Instant now = clock.now();
    Instant nextRunAt = read(actor.userId(), () -> ForecastSchedule.nextRun(
        now,
        repository.latestForecastRun().map(r -> new ForecastSchedule.LastRun(r.at(), r.degraded())),
        repository.activeModel(ModelViews.DEMAND_FORECAST).isPresent(),
        Clock.OPERATING_ZONE));
    return new ForecastOverviewView(
        depot, rows.isEmpty() ? OverviewStatus.NONE : OverviewStatus.READY, label, degraded, generatedAt, out,
        nextRunAt);
  }

  /**
   * A depot outside the actor's scope is {@code 403} plus an audit row, never an
   * empty forecast: rows would be hidden by row-level security, but the calendar
   * and fleet beside them are not, and "no forecast" would be a false answer.
   */
  private void requireDepot(Actor actor, String depot) {
    boolean inScope = read(actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok")));
    if (!inScope) {
      String resource = "wpt:ml:forecast:" + depot;
      String reason = "outside the actor's scope";
      audit.recordStandalone(com.waypoint.dispatch.platform.audit.AuditEntry.denied(
          actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  private static int weekKey(LocalDate day) {
    return day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) * 100
        + day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
  }

  // ---- internals --------------------------------------------------------------------------

  private Optional<PlanPredictionsView> predictions(UUID planId) {
    return repository.scoring(planId).map(row -> new PlanPredictionsView(
        scoring(row),
        repository.predictions(planId).stream()
            .map(p -> new StopPredictionView(
                (UUID) p.get("order_id"), (UUID) p.get("trip_id"), ((Number) p.get("stop_sequence")).intValue(),
                (String) p.get("outlet_id"), (BigDecimal) p.get("service_min"), (BigDecimal) p.get("late_prob"),
                (String) p.get("model_label"), (Boolean) p.get("degraded")))
            .toList()));
  }

  private List<DemandForecast> forecasts(String depot, String brand, int fromKey, int toKey) {
    return repository.latestForecasts(depot, brand, fromKey, toKey).stream()
        .map(f -> new DemandForecast(
            (String) f.get("depot_code"), (String) f.get("brand_code"), ((Number) f.get("iso_year")).intValue(),
            ((Number) f.get("iso_week")).intValue(), (BigDecimal) f.get("total_m3"), (BigDecimal) f.get("chilled_m3"),
            (String) f.get("model_label"), (Boolean) f.get("degraded")))
        .toList();
  }

  /** R-RCP-06, read through the owning modules' contracts as the ambient actor. */
  private Optional<SupplyProbabilityView> supply(UUID orderId) {
    Optional<OrderView> found = orders.order(orderId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    OrderView o = found.get();
    LocalDate day = o.deliveryDate();
    SupplyPolicy.State state = o.status() == OrderStatus.CANCELLED ? SupplyPolicy.State.CANCELLED
        : DONE.contains(o.status()) ? SupplyPolicy.State.DELIVERED : SupplyPolicy.State.OPEN;
    Optional<PlanView> plan = plans.publishedPlan(o.depotCode(), day);
    boolean allocated = plan.map(p -> p.trips().stream()
        .anyMatch(t -> t.stops().stream().anyMatch(s -> s.orderId().equals(orderId)))).orElse(false);
    int failures = 0;
    int attempts = 0;
    int deferred = 0;
    int planned = 0;
    if (state == SupplyPolicy.State.OPEN && plan.isPresent() && allocated) {
      for (StopActualView a : outletHistory(o.depotCode(), o.outletId(), day)) {
        attempts++;
        failures += FAILED.contains(a.outcome()) ? 1 : 0;
      }
    } else if (state == SupplyPolicy.State.OPEN && plan.isEmpty()) {
      LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
      for (LocalDate d = today.minusDays(28); d.isBefore(today); d = d.plusDays(1)) {
        Optional<PlanView> past = plans.publishedPlan(o.depotCode(), d);
        if (past.isPresent()) {
          planned += past.get().trips().stream().mapToInt(t -> t.stops().size()).sum();
          deferred += plans.deferralsFor(o.depotCode(), d).size();
        }
      }
    }
    var e = SupplyPolicy.estimate(
        new SupplyPolicy.Inputs(state, plan.isPresent(), allocated, failures, attempts, deferred, planned));
    return Optional.of(new SupplyProbabilityView(
        orderId, day, e.probability(), e.basis(), PredictionViews.DETERMINISTIC, false));
  }

  /** The outlet's stops over the eight weeks before {@code day}, read through Execution's contract. */
  private List<StopActualView> outletHistory(String depot, String outletId, LocalDate day) {
    List<StopActualView> out = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      Page<StopActualView> page = execution.actuals(depot, day.minusWeeks(8), day.minusDays(1), cursor, Page.MAX_LIMIT);
      page.items().stream().filter(a -> a.outletId().equals(outletId)).forEach(out::add);
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  static PlanScoringView scoring(Map<String, Object> row) {
    return new PlanScoringView(
        (UUID) row.get("plan_id"),
        (String) row.get("depot_code"),
        ((Date) row.get("service_date")).toLocalDate(),
        ScoringStatus.valueOf(((String) row.get("status")).toUpperCase(Locale.ROOT)),
        Optional.ofNullable((String) row.get("model_label")),
        Optional.ofNullable((String) row.get("road_conditions")),
        Optional.ofNullable((String) row.get("reason")),
        Optional.ofNullable(row.get("scored_at")).map(JdbcIntelligenceRepository::instant));
  }

  private static TrainingDeliveryView training(StopActualView a) {
    return new TrainingDeliveryView(
        a.deliveryId(), a.orderId(), a.outletId(), a.vehicleId(), a.serviceDate(), Optional.of(a.plannedArrival()),
        a.windowOpen(), a.windowClose(), a.arrivedAt(), a.completedAt(), a.waitMinutes(), a.serviceMinutes(),
        a.lateMinutes(), a.outcome().name(), a.timingUncertain());
  }

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.ML, actorId, work);
  }
}
