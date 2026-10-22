package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.Backtest;
import com.waypoint.dispatch.intelligence.domain.ForecastRange;
import com.waypoint.dispatch.intelligence.domain.ModelMetrics;
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
import com.waypoint.dispatch.intelligence.infrastructure.JdbcScoringInputs;
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
  private final com.waypoint.dispatch.intelligence.infrastructure.JdbcModelQuality quality;
  private final com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes outcomes;
  private final JdbcScoringInputs scoringInputs;
  private final PlanQuery plans;
  private final OrderQuery orders;
  private final ExecutionQuery execution;
  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Clock clock;
  private final com.waypoint.dispatch.platform.audit.AuditLog audit;
  private final ReferencePayload payload;

  public IntelligenceDataQuery(
      Database database,
      JdbcIntelligenceRepository repository,
      PlanQuery plans,
      OrderQuery orders,
      ExecutionQuery execution,
      ReferenceQuery reference,
      Metrics metrics,
      Clock clock,
      com.waypoint.dispatch.platform.audit.AuditLog audit,
      com.waypoint.dispatch.intelligence.infrastructure.JdbcModelQuality quality,
      com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes outcomes,
      JdbcScoringInputs scoringInputs,
      ReferencePayload payload) {
    this.payload = payload;
    this.outcomes = outcomes;
    this.scoringInputs = scoringInputs;
    this.quality = quality;
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

  /** Issue #12, R-ML-40: the newest shadow reviews, what an administrator reads before promoting a model. */
  public List<com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowReviewView> shadowReviews(
      Actor actor, int limit) {
    if (limit < 1 || limit > 100) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "limit must be from 1 to 100");
    }
    return read(actor.userId(), () -> repository.reviews(limit));
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
   * Issue #7: every order of a depot as demand, keyset-paged on placement, as
   * the demand model's training history. A depot outside the reader's scope is
   * refused and audited, never an empty page that reads as "no orders".
   */
  public Page<PredictionViews.TrainingOrderView> trainingOrders(
      Actor actor, String depot, LocalDate from, LocalDate to, Optional<String> cursor, Integer limit) {
    if (from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "from is after to");
    }
    requireDepot(actor, depot, EXPORT, "wpt:ml:training:" + depot);
    Page<com.waypoint.dispatch.ordering.contract.OrderViews.OrderHistoryView> page =
        read(actor.userId(), () -> orders.orderHistory(depot, from, to, cursor, Page.limit(limit)));
    return new Page<>(page.items().stream()
        .map(o -> new PredictionViews.TrainingOrderView(o.orderId(), o.placedDate(), o.outletId(), o.brandCode(),
            o.depotCode(), o.temperature(), o.volumeM3(), o.status().name().toLowerCase(java.util.Locale.ROOT)))
        .toList(), page.nextCursor());
  }

  /** Depots one reference export may name, and the longest calendar it may span. */
  static final int TRAINING_REFERENCE_DEPOTS = 20;
  static final int TRAINING_REFERENCE_DAYS = 2_000;

  /**
   * Issue #12, R-ML-38: the reference tables a retraining run reads, built as a
   * scoring request builds them, so training and serving read the same days.
   * Every depot named must be in the reader's scope; one outside it is refused
   * and audited, never left out of the answer.
   */
  public Map<String, Object> trainingReference(Actor actor, List<String> depots, LocalDate from, LocalDate to) {
    if (depots.isEmpty() || depots.size() > TRAINING_REFERENCE_DEPOTS) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "name from 1 to " + TRAINING_REFERENCE_DEPOTS + " depots");
    }
    if (from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "from is after to");
    }
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= TRAINING_REFERENCE_DAYS) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "the calendar spans at most " + TRAINING_REFERENCE_DAYS + " days");
    }
    List<String> distinct = depots.stream().distinct().toList();
    for (String depot : distinct) {
      requireDepot(actor, depot, EXPORT, "wpt:ml:training:" + depot);
    }
    UUID version = reference.currentVersionId().orElseThrow(
        () -> new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "No reference version is loaded"));
    metrics.increment("waypoint.ml.training_reference_exported");
    return payload.forTraining(distinct, from, to, version);
  }

  /**
   * Roadmap MF-05: the depot's scored routes with their outcomes, keyset-paged
   * on (day, plan, route), as the training input. Rows outside the reader's
   * depots are filtered in SQL.
   */
  public Page<PredictionViews.TrainingRouteView> trainingRoutes(
      Actor actor, String depot, LocalDate from, LocalDate to, Optional<String> cursor, Integer limit) {
    if (from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "from is after to");
    }
    int size = Page.limit(limit);
    Optional<List<String>> after = cursor.map(c -> com.waypoint.dispatch.shared.domain.Cursor.decode(c, 3));
    List<JdbcScoringInputs.Row> rows = read(actor.userId(), () -> scoringInputs.page(depot, from, to, after, size));
    Page<JdbcScoringInputs.Row> page = Page.fromOverfetch(rows, size, r -> com.waypoint.dispatch.shared.domain.Cursor
        .encode(r.serviceDate().toString(), r.planId().toString(), r.routeId()));
    return new Page<>(page.items().stream()
        .map(r -> new PredictionViews.TrainingRouteView(
            r.planId(), r.routeId(), r.serviceDate(), r.referenceVersionId(), r.route(), r.labels()))
        .toList(), page.nextCursor());
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

    Optional<Instant> generatedAt = rows.stream()
        .map(r -> JdbcIntelligenceRepository.instant(r.get("generated_at")))
        .max(java.util.Comparator.naturalOrder());
    Optional<String> label = rows.stream().map(r -> (String) r.get("model_label")).distinct()
        .reduce((a, b) -> "mixed");
    boolean degraded = rows.stream().anyMatch(r -> (Boolean) r.get("degraded"));

    // MF-06: how far the last 26 finished weeks missed, pooled across every series.
    LocalDate thisMonday = origin.minusWeeks(1);
    Optional<ForecastRange.Factors> factors = database.asSystemSeparately(ModuleRole.ML,
        () -> ForecastRange.factors(quality.pairs(weekKey(thisMonday.minusWeeks(26)), weekKey(thisMonday.minusWeeks(1)))));
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
          .map(r -> {
            BigDecimal total = (BigDecimal) r.get("total_m3");
            Optional<double[]> range = factors.map(f -> ForecastRange.apply(total.doubleValue(), f));
            return new BrandVolumeView(
                (String) r.get("brand_code"), total, (BigDecimal) r.get("chilled_m3"),
                range.map(x -> BigDecimal.valueOf(x[0]).setScale(1, java.math.RoundingMode.HALF_UP)),
                range.map(x -> BigDecimal.valueOf(x[1]).setScale(1, java.math.RoundingMode.HALF_UP)));
          })
          .sorted(java.util.Comparator.comparing(BrandVolumeView::brandCode))
          .toList();
      int operating = (int) week.stream().filter(CalendarDayView::operating).count();
      // Each day's own fleet: a vehicle in the workshop that day carries nothing (T2-01).
      FleetCapacity.Week capacity = FleetCapacity.weeklyByDay(week.stream()
          .filter(CalendarDayView::operating)
          .map(day -> new FleetCapacity.Day(
              reference.availableVehicles(depot, day.date(), null).stream()
                  .map(v -> new FleetCapacity.Vehicle(v.volumeCapM3(), v.refrigerated()))
                  .toList(),
              plans.maxTripsFor(day.date())))
          .toList());
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
        repository.activeModel(ModelViews.DEMAND_FORECAST).map(m -> m.view().activatedAt().orElse(Instant.MIN)),
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
  /**
   * Roadmap MF-04, R-ML-16: each model's stored predictions over the last
   * {@code weeks} finished weeks beside what happened, judged against the
   * built-in estimate. Only the reader's depots count.
   */
  public PredictionViews.BacktestView backtest(Actor actor, int weeks) {
    if (weeks < 1 || weeks > 12) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "weeks must be from 1 to 12");
    }
    LocalDate thisMonday = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate()
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    LocalDate from = thisMonday.minusWeeks(weeks);
    LocalDate to = thisMonday.minusDays(1);
    List<Map<String, Object>> rows = read(actor.userId(), () -> outcomes.scoredOutcomes(from, to));
    Map<String, List<Backtest.Outcome>> byModel = new java.util.HashMap<>();
    for (Map<String, Object> r : rows) {
      Object late = r.get("late_label");
      byModel.computeIfAbsent((String) r.get("model_label"), k -> new java.util.ArrayList<>())
          .add(new Backtest.Outcome(((java.sql.Date) r.get("service_date")).toLocalDate(),
              Optional.of(((BigDecimal) r.get("predicted_late_prob")).doubleValue()),
              Optional.ofNullable((Boolean) late)));
    }
    List<PredictionViews.ModelBacktestView> models = Backtest.run(byModel).stream()
        .map(b -> new PredictionViews.ModelBacktestView(b.modelLabel(), b.stops(), b.labelled(),
            BigDecimal.valueOf(b.coverage()).setScale(4, java.math.RoundingMode.HALF_UP),
            ratio(b.brier()), ratio(b.logLoss()),
            ratio(b.calibration().map(ModelMetrics.Calibration::slope)),
            ratio(b.calibration().map(ModelMetrics.Calibration::intercept)),
            b.verdict().name(),
            b.weeks().stream().map(w -> new PredictionViews.BacktestWeekView(
                w.isoYear(), w.isoWeek(), w.stops(), w.labelled(), ratio(w.brier()))).toList()))
        .toList();
    return new PredictionViews.BacktestView(from, to, Backtest.MIN_LABELS, models);
  }

  /**
   * Roadmap T2-03: how the last {@code weeks} finished weeks' forecasts did, as
   * made before each week began, beside what was ordered. A depot outside scope is 403.
   */
  public PredictionViews.ForecastAccuracyView forecastAccuracy(Actor actor, String depot, int weeks) {
    if (weeks < 1 || weeks > 12) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "weeks must be from 1 to 12");
    }
    requireDepot(actor, depot);
    LocalDate thisMonday = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate()
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    int fromKey = weekKey(thisMonday.minusWeeks(weeks));
    int toKey = weekKey(thisMonday.minusWeeks(1));
    List<Map<String, Object>> rows = read(actor.userId(), () -> quality.accuracy(depot, fromKey, toKey));
    Map<Integer, List<Map<String, Object>>> byWeek = new java.util.TreeMap<>();
    for (Map<String, Object> r : rows) {
      int key = ((Number) r.get("iso_year")).intValue() * 100 + ((Number) r.get("iso_week")).intValue();
      byWeek.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(r);
    }
    List<PredictionViews.WeekAccuracyView> out = new java.util.ArrayList<>();
    List<double[]> all = new java.util.ArrayList<>();
    List<double[]> compared = new java.util.ArrayList<>();
    List<double[]> baselineAll = new java.util.ArrayList<>();
    for (List<Map<String, Object>> week : byWeek.values()) {
      List<PredictionViews.BrandAccuracyView> brands = new java.util.ArrayList<>();
      List<double[]> pairs = new java.util.ArrayList<>();
      BigDecimal forecast = BigDecimal.ZERO;
      BigDecimal ordered = BigDecimal.ZERO;
      List<double[]> baselinePairs = new java.util.ArrayList<>();
      for (Map<String, Object> r : week) {
        BigDecimal f = (BigDecimal) r.get("forecast_total_m3");
        BigDecimal a = (BigDecimal) r.get("actual_total_m3");
        Optional<BigDecimal> b = Optional.ofNullable((BigDecimal) r.get("baseline_total_m3"));
        brands.add(new PredictionViews.BrandAccuracyView((String) r.get("brand_code"), f, a, b));
        pairs.add(new double[] {f.doubleValue(), a.doubleValue()});
        // T2-03: the forecast and the 8-week mean, on the same brand-weeks only.
        b.ifPresent(base -> {
          compared.add(new double[] {f.doubleValue(), a.doubleValue()});
          baselinePairs.add(new double[] {base.doubleValue(), a.doubleValue()});
        });
        forecast = forecast.add(f);
        ordered = ordered.add(a);
      }
      all.addAll(pairs);
      baselineAll.addAll(baselinePairs);
      Map<String, Object> first = week.get(0);
      out.add(new PredictionViews.WeekAccuracyView(
          ((Number) first.get("iso_year")).intValue(), ((Number) first.get("iso_week")).intValue(),
          (String) first.get("model_label"), forecast, ordered, ratio(ModelMetrics.wape(pairs)), brands,
          ratio(ModelMetrics.wape(baselinePairs))));
    }
    return new PredictionViews.ForecastAccuracyView(depot, out, ratio(ModelMetrics.wape(all)),
        ratio(ModelMetrics.wape(compared)), ratio(ModelMetrics.wape(baselineAll)));
  }

  private static Optional<BigDecimal> ratio(Optional<Double> value) {
    return value.map(v -> BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP));
  }

  private void requireDepot(Actor actor, String depot) {
    requireDepot(actor, depot, READ, "wpt:ml:forecast:" + depot);
  }

  private void requireDepot(Actor actor, String depot, String action, String resource) {
    boolean inScope = read(actor.userId(), () -> Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok")));
    if (!inScope) {
      String reason = "outside the actor's scope";
      audit.recordStandalone(com.waypoint.dispatch.platform.audit.AuditEntry.denied(
          actor.userId(), actor.deviceId(), action, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  private static int weekKey(LocalDate day) {
    return day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) * 100
        + day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
  }

  // ---- internals --------------------------------------------------------------------------

  private Optional<PlanPredictionsView> predictions(UUID planId) {
    return repository.scoring(planId).map(row -> {
      Map<UUID, List<PredictionViews.LateReasonView>> reasons = lateReasons(planId);
      return new PlanPredictionsView(
          scoring(row),
          repository.predictions(planId).stream()
              .map(p -> new StopPredictionView(
                  (UUID) p.get("order_id"), (UUID) p.get("trip_id"), ((Number) p.get("stop_sequence")).intValue(),
                  (String) p.get("outlet_id"), (BigDecimal) p.get("service_min"), (BigDecimal) p.get("late_prob"),
                  (String) p.get("model_label"), (Boolean) p.get("degraded"),
                  reasons.getOrDefault((UUID) p.get("order_id"), List.of()),
                  Optional.ofNullable((BigDecimal) p.get("service_low")),
                  Optional.ofNullable((BigDecimal) p.get("service_high"))))
              .toList(),
          reference.roadConditionsUntil());
    });
  }

  /** How far back a store's late visits are counted for its reason. */
  static final int HISTORY_WEEKS = 8;

  /**
   * Roadmap MF-08: each stop's reasons, from the plan's own times and the
   * store's labelled visits of the last eight weeks (R-ML-12). Empty when the
   * plan cannot be read.
   */
  private Map<UUID, List<PredictionViews.LateReasonView>> lateReasons(UUID planId) {
    Optional<com.waypoint.dispatch.planning.contract.PlanViews.PlanView> plan = plans.plan(planId);
    if (plan.isEmpty()) {
      return Map.of();
    }
    LocalDate day = plan.get().serviceDate();
    List<String> outlets = plan.get().trips().stream().flatMap(t -> t.stops().stream())
        .map(com.waypoint.dispatch.planning.contract.PlanViews.StopView::outletId).distinct().toList();
    Map<String, int[]> history = outcomes.lateCounts(outlets, day.minusWeeks(HISTORY_WEEKS), day.minusDays(1));
    Map<UUID, List<PredictionViews.LateReasonView>> out = new java.util.HashMap<>();
    for (var trip : plan.get().trips()) {
      List<com.waypoint.dispatch.planning.contract.PlanViews.StopView> stops = trip.stops().stream()
          .sorted(java.util.Comparator.comparingInt(com.waypoint.dispatch.planning.contract.PlanViews.StopView::sequence))
          .toList();
      for (int i = 0; i < stops.size(); i++) {
        var s = stops.get(i);
        long slack = java.time.Duration.between(s.plannedArrival(), s.windowClose()).toMinutes();
        int[] h = history.getOrDefault(s.outletId(), new int[2]);
        out.put(s.orderId(), com.waypoint.dispatch.intelligence.domain.LateReasons.of(slack, i, h[0], h[1]).stream()
            .map(r -> new PredictionViews.LateReasonView(r.code(), r.value(), r.of()))
            .toList());
      }
    }
    return out;
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
