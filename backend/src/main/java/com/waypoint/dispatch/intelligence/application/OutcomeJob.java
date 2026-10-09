package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.StopActualView;
import com.waypoint.dispatch.intelligence.domain.OutcomeLabel;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Every ten minutes, the outcomes of today's and yesterday's stops are put
 * beside their predictions (roadmap MF-02, R-ML-12), read through Execution's
 * actuals contract. A record can still change during the day (a redo, a late
 * proof), so a row is refreshed, never added twice. The share of stops with a
 * label is a gauge per depot, so a metric on few labels is never read as good.
 */
@Component
public class OutcomeJob implements ScheduledJob {
  private final Database database;
  private final ExecutionQuery execution;
  private final ReferenceQuery reference;
  private final JdbcOutcomes outcomes;
  private final Metrics metrics;
  private final Map<String, AtomicLong> coveragePercent = new ConcurrentHashMap<>();

  OutcomeJob(
      Database database, ExecutionQuery execution, ReferenceQuery reference, JdbcOutcomes outcomes, Metrics metrics) {
    this.database = database;
    this.execution = execution;
    this.reference = reference;
    this.outcomes = outcomes;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "intelligence.outcomes";
  }

  @Override
  public String cron() {
    return "0 */10 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many stops were recorded */
  public int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    int recorded = 0;
    Map<String, Optional<double[]>> outletPoints = new java.util.HashMap<>();
    for (String depot : database.asSystem(ModuleRole.ML, reference::depotCodes)) {
      List<StopActualView> stops = new java.util.ArrayList<>();
      Optional<String> cursor = Optional.empty();
      do {
        Optional<String> at = cursor;
        Page<StopActualView> page =
            database.asSystem(ModuleRole.ML, () -> execution.actuals(depot, today.minusDays(1), today, at, Page.MAX_LIMIT));
        stops.addAll(page.items());
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      for (StopActualView a : stops) {
        OutcomeLabel.Label label = label(a, stops, outletPoints);
        database.asSystem(ModuleRole.ML, () -> outcomes.record(a, label, now));
      }
      recorded += stops.size();
      JdbcOutcomes.Coverage c = database.asSystem(ModuleRole.ML, () -> outcomes.coverage(depot, today));
      gauge(depot).set(Math.round(100 * OutcomeLabel.coverage(c.labelled(), c.stops())));
    }
    return recorded;
  }

  /** Before this, a vehicle's previous stop is not known: the GPS window looks back this far at most. */
  static final java.time.Duration LOOK_BACK = java.time.Duration.ofHours(3);

  /**
   * The tap's label when it is trustworthy; otherwise the GPS at an outlet with
   * an exact location, between leaving the vehicle's previous stop and finishing
   * this one (R-ML-12).
   */
  private OutcomeLabel.Label label(StopActualView a, List<StopActualView> day, Map<String, Optional<double[]>> points) {
    OutcomeLabel.Label tapped = OutcomeLabel.of(a.arrivedAt(), a.timingUncertain(), a.lateMinutes());
    if (tapped.source() != OutcomeLabel.Source.NONE || a.completedAt().isEmpty()) {
      return tapped;
    }
    Optional<double[]> outlet = points.computeIfAbsent(a.outletId(), id -> database.asSystem(ModuleRole.ML,
        () -> reference.outlet(id, null)
            .flatMap(o -> o.location())
            .filter(g -> "exact".equals(g.precision()))
            .map(g -> new double[] {g.latitude().doubleValue(), g.longitude().doubleValue()})));
    if (outlet.isEmpty()) {
      return tapped;
    }
    Instant to = a.completedAt().get();
    Instant from = day.stream()
        .filter(o -> o.vehicleId().equals(a.vehicleId()) && o.serviceDate().equals(a.serviceDate()))
        .flatMap(o -> o.completedAt().stream())
        .filter(t -> t.isBefore(to))
        .max(Instant::compareTo)
        .orElse(to.minus(LOOK_BACK));
    List<OutcomeLabel.Fix> fixes = database.asSystem(ModuleRole.ML, () -> execution.vehicleFixes(a.vehicleId(), from, to, 2000))
        .stream()
        .map(p -> new OutcomeLabel.Fix(p.recordedAt(), p.latitude().doubleValue(), p.longitude().doubleValue(), p.lowQuality()))
        .toList();
    Instant close = a.serviceDate().atTime(a.windowClose()).atZone(Clock.OPERATING_ZONE).toInstant();
    return OutcomeLabel.of(a.arrivedAt(), a.timingUncertain(), a.lateMinutes(), outlet, fixes, close);
  }

  private AtomicLong gauge(String depot) {
    return coveragePercent.computeIfAbsent(depot, d -> {
      AtomicLong value = new AtomicLong(100);
      metrics.gauge("waypoint.ml.label_coverage_percent", value::get, "depot", d);
      return value;
    });
  }
}
