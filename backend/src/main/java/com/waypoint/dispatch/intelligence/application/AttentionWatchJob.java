package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionKind;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionSeverity;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Finding;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Progress;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.StopFacts;
import com.waypoint.dispatch.intelligence.domain.AttentionThresholds;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAttentionRepository;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Watches every live trip so one dispatcher does not have to (issue #268).
 *
 * <p>Every minute, for each depot: the vehicles of today's published plan, each
 * one's run sheet, and {@link AttentionPolicy} on every stop. What it finds is
 * raised once and refreshed while it holds; what it no longer finds is ended.
 * All of it is read through Planning's and Execution's contracts, and the
 * dispatcher still decides: this only watches.
 *
 * <p>The heartbeat is written even when nothing is found, so a screen can say
 * when the watch last looked. A stalled watch must not read as a calm day.
 */
@Component
public class AttentionWatchJob implements ScheduledJob {
  private final Database database;
  private final JdbcAttentionRepository repository;
  private final PlanQuery plans;
  private final ExecutionQuery execution;
  private final ReferenceQuery reference;
  private final Metrics metrics;

  public AttentionWatchJob(
      Database database,
      JdbcAttentionRepository repository,
      PlanQuery plans,
      ExecutionQuery execution,
      ReferenceQuery reference,
      Metrics metrics) {
    this.database = database;
    this.repository = repository;
    this.plans = plans;
    this.execution = execution;
    this.reference = reference;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "ml.attention-watch";
  }

  @Override
  public String cron() {
    return "0 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    for (String depot : reference.depotCodes()) {
      try {
        watch(depot, today, now);
      } catch (RuntimeException e) {
        // One depot that fails is counted and tried again next minute; the others still run.
        metrics.increment("waypoint.ml.attention_watch_failed");
      }
    }
  }

  /** @return how many items are open for the depot after this look */
  int watch(String depot, LocalDate day, Instant now) {
    List<String> vehicles = plans.publishedPlan(depot, day)
        .map(plan -> plan.trips().stream().map(TripView::vehicleId).distinct().sorted().toList())
        .orElse(List.of());
    List<RunSheetView> sheets = new ArrayList<>();
    for (String vehicle : vehicles) {
      execution.runSheet(vehicle, day).ifPresent(sheets::add);
    }
    return database.asSystem(ModuleRole.ML, () -> {
      AttentionThresholds thresholds = repository.thresholds(depot);
      int open = 0;
      for (RunSheetView sheet : sheets) {
        for (RunSheetStopView stop : sheet.stops()) {
          Optional<Finding> finding = AttentionPolicy.assess(facts(stop, day), thresholds, now);
          if (finding.isEmpty()) {
            continue;
          }
          Finding found = finding.get();
          boolean raised = repository.raise(
              stop.deliveryId(), AttentionKind.valueOf(found.kind().name()), AttentionSeverity.valueOf(found.severity().name()),
              depot, day, sheet.vehicleId(), stop.tripId(), stop.outletId(),
              found.minutesLeft().map(Long::intValue), now);
          if (raised) {
            metrics.increment("waypoint.ml.attention_raised", "kind", found.kind().name().toLowerCase(java.util.Locale.ROOT));
          }
          open++;
        }
      }
      repository.clearUnseen(depot, day, now);
      repository.beat(depot, sheets.size(), now);
      return open;
    });
  }

  /** One stop as the rule reads it: the day's clock times become instants in the depot's zone. */
  static StopFacts facts(RunSheetStopView stop, LocalDate day) {
    Progress progress = switch (stop.outcome()) {
      case PENDING, ARRIVED -> Progress.OPEN;
      case DELIVERED, PARTIAL -> Progress.DELIVERED;
      case FAILED -> Progress.FAILED;
      case SKIPPED -> Progress.SKIPPED;
    };
    return new StopFacts(
        progress, at(day, stop.plannedArrival()), stop.expectedArrival(), at(day, stop.windowClose()),
        stop.completedAt(), stop.proofCaptured());
  }

  private static Instant at(LocalDate day, LocalTime time) {
    return day.atTime(time).atZone(Clock.OPERATING_ZONE).toInstant();
  }
}
