package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics;
import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes.DailyMetrics;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes.Row;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Each night, how every model did on the last days' outcomes, per depot
 * (roadmap MF-03, R-ML-13): written to {@code ml.model_metrics_daily} and kept
 * as gauges for Prometheus, so an alert fires when calibration or error moves.
 * The last three days are recomputed, as a late record still changes them.
 */
@Component
public class ModelMetricsJob implements ScheduledJob {
  static final int DAYS = 3;
  /** A fitted slope or intercept beyond this is a degenerate fit, not a measurement. */
  private static final double SANE = 1000;

  private final Database database;
  private final ReferenceQuery reference;
  private final JdbcOutcomes outcomes;
  private final Metrics metrics;
  private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

  ModelMetricsJob(Database database, ReferenceQuery reference, JdbcOutcomes outcomes, Metrics metrics) {
    this.database = database;
    this.reference = reference;
    this.outcomes = outcomes;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "intelligence.model-metrics";
  }

  @Override
  public String cron() {
    return "0 20 3 * * *";
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

  /** @return the metrics written */
  public List<DailyMetrics> runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    List<DailyMetrics> written = new ArrayList<>();
    for (String depot : database.asSystem(ModuleRole.ML, reference::depotCodes)) {
      for (int back = DAYS; back >= 1; back--) {
        LocalDate day = today.minusDays(back);
        List<Row> rows = database.asSystem(ModuleRole.ML, () -> outcomes.predicted(depot, day));
        Map<String, List<Row>> byModel = rows.stream().collect(Collectors.groupingBy(Row::modelLabel, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Row>> e : byModel.entrySet()) {
          DailyMetrics m = measure(depot, day, e.getKey(), e.getValue());
          database.asSystem(ModuleRole.ML, () -> outcomes.saveMetrics(m, now));
          written.add(m);
          if (back == 1) {
            publish(m);
          }
        }
      }
    }
    return written;
  }

  static DailyMetrics measure(String depot, LocalDate day, String model, List<Row> rows) {
    List<Scored> scored = new ArrayList<>();
    List<double[]> service = new ArrayList<>();
    for (Row r : rows) {
      if (r.lateProb().isPresent() && r.late().isPresent()) {
        scored.add(new Scored(r.lateProb().get(), r.late().get()));
      }
      if (r.predictedService().isPresent() && r.serviceMinutes().isPresent()) {
        service.add(new double[] {r.predictedService().get(), r.serviceMinutes().get()});
      }
    }
    Optional<ModelMetrics.Calibration> cal =
        ModelMetrics.calibration(scored).filter(c -> Math.abs(c.slope()) < SANE && Math.abs(c.intercept()) < SANE);
    return new DailyMetrics(
        depot, day, model, rows.size(), scored.size(), ModelMetrics.brier(scored), ModelMetrics.logLoss(scored),
        cal.map(ModelMetrics.Calibration::slope), cal.map(ModelMetrics.Calibration::intercept),
        ModelMetrics.meanAbsoluteError(service), service.size());
  }

  /** Yesterday's values as gauges; a value not measured stays NaN, never a flattering zero. */
  private void publish(DailyMetrics m) {
    gauge("waypoint.ml.brier", m).set(m.brier().orElse(Double.NaN));
    gauge("waypoint.ml.log_loss", m).set(m.logLoss().orElse(Double.NaN));
    gauge("waypoint.ml.calibration_slope", m).set(m.calSlope().orElse(Double.NaN));
    gauge("waypoint.ml.calibration_intercept", m).set(m.calIntercept().orElse(Double.NaN));
    gauge("waypoint.ml.service_mae_minutes", m).set(m.serviceMae().orElse(Double.NaN));
  }

  private AtomicReference<Double> gauge(String name, DailyMetrics m) {
    return gauges.computeIfAbsent(name + "|" + m.depotCode() + "|" + m.modelLabel(), k -> {
      AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
      metrics.gauge(name, value::get, "depot", m.depotCode(), "model", m.modelLabel());
      return value;
    });
  }
}
