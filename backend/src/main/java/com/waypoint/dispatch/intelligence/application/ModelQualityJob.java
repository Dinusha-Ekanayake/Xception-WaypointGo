package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcModelQuality;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcModelQuality.WeekForecast;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.DailyVolumeView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Each night, the rest of the daily model metrics (roadmap MF-03, R-ML-13):
 * <ul>
 *   <li>the demand forecast of each of the last four finished weeks, as made
 *       before the week began, beside what was ordered (placement dates, as the
 *       forecast is built), with the WAPE over the depot's brands as a gauge;
 *   <li>the drift of the predicted late probabilities: the last 7 days against
 *       the 28 days before, as a PSI per depot and model.
 * </ul>
 * Labels are not needed for either, so both are known before outcomes arrive.
 */
@Component
public class ModelQualityJob implements ScheduledJob {
  static final int WEEKS = 4;

  private final Database database;
  private final ReferenceQuery reference;
  private final OrderQuery orders;
  private final JdbcModelQuality quality;
  private final Metrics metrics;
  private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

  ModelQualityJob(Database database, ReferenceQuery reference, OrderQuery orders, JdbcModelQuality quality, Metrics metrics) {
    this.database = database;
    this.reference = reference;
    this.orders = orders;
    this.quality = quality;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "intelligence.model-quality";
  }

  @Override
  public String cron() {
    return "0 50 3 * * *";
  }

  @Override
  public boolean onBusinessClock() {
    return true;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** What was measured: depot to its last finished week's WAPE, and depot|model to its PSI. */
  public record Measured(Map<String, Optional<Double>> wape, Map<String, Double> psi) {}

  public Measured runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    List<String> depots = database.asSystem(ModuleRole.ML, reference::depotCodes);
    List<String> brands = database.asSystem(ModuleRole.ML, reference::brandCodes);
    Map<String, Optional<Double>> wapes = new java.util.LinkedHashMap<>();
    Map<String, Double> psis = new java.util.LinkedHashMap<>();
    for (String depot : depots) {
      List<double[]> lastWeek = new ArrayList<>();
      for (int back = WEEKS; back >= 1; back--) {
        LocalDate monday = thisMonday.minusWeeks(back);
        Instant start = monday.atStartOfDay(Clock.OPERATING_ZONE).toInstant();
        int year = monday.get(IsoFields.WEEK_BASED_YEAR);
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        for (String brand : brands) {
          Optional<WeekForecast> forecast =
              database.asSystem(ModuleRole.ML, () -> quality.forecastBefore(depot, brand, year, week, start));
          if (forecast.isEmpty()) {
            continue;
          }
          List<DailyVolumeView> days =
              database.asSystem(ModuleRole.ML, () -> orders.dailyVolumes(depot, brand, monday, monday.plusDays(6)));
          BigDecimal total = days.stream().map(DailyVolumeView::totalM3).reduce(BigDecimal.ZERO, BigDecimal::add);
          BigDecimal chilled = days.stream().map(DailyVolumeView::chilledM3).reduce(BigDecimal.ZERO, BigDecimal::add);
          database.asSystem(ModuleRole.ML,
              () -> quality.saveAccuracy(depot, brand, year, week, forecast.get(), total, chilled, now));
          if (back == 1) {
            lastWeek.add(new double[] {forecast.get().totalM3().doubleValue(), total.doubleValue()});
          }
        }
      }
      Optional<Double> wape = ModelMetrics.wape(lastWeek);
      wapes.put(depot, wape);
      gauge("waypoint.ml.forecast_wape", depot, null).set(wape.orElse(Double.NaN));

      Instant currentFrom = today.minusDays(7).atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      Instant currentTo = today.atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      Instant referenceFrom = today.minusDays(35).atStartOfDay(Clock.OPERATING_ZONE).toInstant();
      for (String model : database.asSystem(ModuleRole.ML, () -> quality.modelsScoring(depot, currentFrom, currentTo))) {
        List<Double> ref = database.asSystem(ModuleRole.ML,
            () -> quality.lateProbabilities(depot, model, referenceFrom, currentFrom));
        List<Double> cur = database.asSystem(ModuleRole.ML,
            () -> quality.lateProbabilities(depot, model, currentFrom, currentTo));
        Optional<Double> psi = ModelMetrics.psi(ref, cur);
        if (psi.isPresent()) {
          double value = psi.get();
          database.asSystem(ModuleRole.ML,
              () -> quality.saveDrift(depot, today.minusDays(1), model, value, ref.size(), cur.size(), now));
          psis.put(depot + "|" + model, value);
        }
        gauge("waypoint.ml.prediction_psi", depot, model).set(psi.orElse(Double.NaN));
      }
    }
    return new Measured(wapes, psis);
  }

  private AtomicReference<Double> gauge(String name, String depot, String model) {
    return gauges.computeIfAbsent(name + "|" + depot + "|" + model, k -> {
      AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
      if (model == null) {
        metrics.gauge(name, value::get, "depot", depot);
      } else {
        metrics.gauge(name, value::get, "depot", depot, "model", model);
      }
      return value;
    });
  }
}
