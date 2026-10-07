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
    for (String depot : database.asSystem(ModuleRole.ML, reference::depotCodes)) {
      Optional<String> cursor = Optional.empty();
      do {
        Optional<String> at = cursor;
        Page<StopActualView> page =
            database.asSystem(ModuleRole.ML, () -> execution.actuals(depot, today.minusDays(1), today, at, Page.MAX_LIMIT));
        database.asSystem(
            ModuleRole.ML,
            () -> page.items().forEach(a -> outcomes.record(
                a, OutcomeLabel.of(a.arrivedAt(), a.timingUncertain(), a.lateMinutes()), now)));
        recorded += page.items().size();
        cursor = page.nextCursor();
      } while (cursor.isPresent());
      JdbcOutcomes.Coverage c = database.asSystem(ModuleRole.ML, () -> outcomes.coverage(depot, today));
      gauge(depot).set(Math.round(100 * OutcomeLabel.coverage(c.labelled(), c.stops())));
    }
    return recorded;
  }

  private AtomicLong gauge(String depot) {
    return coveragePercent.computeIfAbsent(depot, d -> {
      AtomicLong value = new AtomicLong(100);
      metrics.gauge("waypoint.ml.label_coverage_percent", value::get, "depot", d);
      return value;
    });
  }
}
