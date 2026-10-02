package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.ModelViews;
import com.waypoint.dispatch.intelligence.contract.PredictionViews;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator.DailyVolume;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator.WeekVolume;
import com.waypoint.dispatch.intelligence.domain.ModelGate;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Decision;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Served;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.Forecast;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcIntelligenceRepository.ModelRow;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.Answer;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.ForecastAnswer;
import com.waypoint.dispatch.intelligence.infrastructure.ModelServingAdapter.WeekAnswer;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The next ten weeks of demand per depot and brand, precomputed every Monday
 * morning so a read never waits on the model service (P-29).
 *
 * <p>The model's forecast when the gate allows it (R-ML-04), else the
 * deterministic weekday mean of the last eight weeks, marked degraded with the
 * reason. Every run is kept; a read takes the newest per week.
 */
@Component
public class ForecastJob implements ScheduledJob {
  static final int WEEKS = 10;

  private final Database database;
  private final JdbcIntelligenceRepository repository;
  private final ModelServingAdapter adapter;
  private final ReferenceQuery reference;
  private final ReferencePayload payload;
  private final OrderQuery orders;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();

  ForecastJob(
      Database database,
      JdbcIntelligenceRepository repository,
      ModelServingAdapter adapter,
      ReferenceQuery reference,
      ReferencePayload payload,
      OrderQuery orders,
      Metrics metrics) {
    this.database = database;
    this.repository = repository;
    this.adapter = adapter;
    this.reference = reference;
    this.payload = payload;
    this.orders = orders;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "ml.demand-forecast";
  }

  @Override
  public String cron() {
    return "0 0 4 * * MON";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  record Key(String depot, String brand, int isoYear, int isoWeek) {}

  /** @return how many depot-brand-week forecasts were written */
  int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate origin = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    LocalDate end = origin.plusWeeks(WEEKS).minusDays(1);
    List<String> depots = reference.depotCodes();
    List<String> brands = reference.brandCodes();
    List<LocalDate> mondays = origin.datesUntil(end.plusDays(1), java.time.Period.ofWeeks(1)).toList();

    Optional<ModelRow> active =
        database.asSystem(ModuleRole.ML, () -> repository.activeModel(ModelViews.DEMAND_FORECAST));
    Decision decision = gate(active);
    Map<Key, WeekVolume> model = new LinkedHashMap<>();
    String label = PredictionViews.DETERMINISTIC;
    Optional<String> reason = decision.reason();
    if (decision.useModel()) {
      List<Map<String, Object>> weeks = new ArrayList<>();
      for (String d : depots) {
        for (String b : brands) {
          for (LocalDate monday : mondays) {
            weeks.add(ReferencePayload.row("depot", d, "brand", b, "isoYear",
                monday.get(IsoFields.WEEK_BASED_YEAR), "isoWeek", monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
          }
        }
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("calendar", payload.calendar(ReferencePayload.CALENDAR_FROM, end));
      body.put("weeks", weeks);
      Answer<ForecastAnswer> answer = adapter.demandForecast(body);
      if (answer.value().isPresent()) {
        label = answer.value().get().modelLabel();
        for (WeekAnswer w : answer.value().get().weeks()) {
          model.put(new Key(w.depot(), w.brand(), w.isoYear(), w.isoWeek()),
              new WeekVolume(scale(w.totalM3()), scale(w.chilledM3().min(w.totalM3()))));
        }
      } else {
        reason = Optional.of("model serving failed: " + answer.failure().orElse("unknown"));
      }
    }

    boolean byModel = !model.isEmpty();
    Map<Key, WeekVolume> results = byModel ? model : deterministic(depots, brands, origin, mondays);
    UUID runId = UuidV7.generate(now, random);
    String modelLabel = label;
    Optional<UUID> modelId = byModel ? active.map(ModelRow::id) : Optional.empty();
    database.asSystem(ModuleRole.ML, () -> results.forEach((k, v) -> repository.insertForecast(
        UuidV7.generate(now, random),
        new Forecast(runId, k.depot(), k.brand(), k.isoYear(), k.isoWeek(), v.totalM3(), v.chilledM3(), modelId,
            modelLabel, !byModel),
        now)));
    metrics.increment("waypoint.ml.forecast", "outcome", byModel ? "model" : "degraded");
    if (!byModel) {
      metrics.increment("waypoint.ml.fallback", "operation", "demand_forecast", "reason",
          PlanScoringJob.category(reason));
    }
    return results.size();
  }

  private Decision gate(Optional<ModelRow> active) {
    Optional<String> label = active.map(m -> m.view().label());
    Served served;
    if (!adapter.configured() || label.isEmpty()) {
      served = Served.none();
    } else {
      Answer<Map<String, String>> loaded = adapter.loadedModels();
      served = loaded.value()
          .map(m -> Optional.ofNullable(m.get(ModelViews.DEMAND_FORECAST)).map(Served::loaded).orElse(Served.none()))
          .orElseGet(() -> Served.unavailable(loaded.failure().orElse("unknown")));
    }
    return ModelGate.decide(ModelViews.DEMAND_FORECAST, adapter.configured(), label, served);
  }

  /** The weekday mean of the eight weeks before the origin, for every depot, brand and week. */
  private Map<Key, WeekVolume> deterministic(
      List<String> depots, List<String> brands, LocalDate origin, List<LocalDate> mondays) {
    Map<Key, WeekVolume> out = new LinkedHashMap<>();
    LocalDate end = mondays.get(mondays.size() - 1).plusDays(6);
    List<CalendarDayView> days = reference.calendarDays(origin, end);
    for (String d : depots) {
      for (String b : brands) {
        List<DailyVolume> history = database.asSystem(ModuleRole.ML, () -> orders
            .dailyVolumes(d, b, origin.minusWeeks(8), origin.minusDays(1)).stream()
            .map(v -> new DailyVolume(v.date(), v.totalM3(), v.chilledM3()))
            .toList());
        for (LocalDate monday : mondays) {
          List<LocalDate> operating = days.stream()
              .filter(c -> !c.date().isBefore(monday) && c.date().isBefore(monday.plusDays(7)) && c.operating())
              .map(CalendarDayView::date)
              .toList();
          out.put(new Key(d, b, monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
              DeterministicEstimator.weekForecast(history, origin, operating));
        }
      }
    }
    return out;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
  }
}
