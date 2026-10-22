package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanPredictionsView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanScoringView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.ScoringStatus;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A draft plan's late risk, scored when the dispatcher looks (R-ML-48), so it
 * can change a decision before the plan is published. The same model and gate
 * as a published plan; nothing is stored where a notice, an outcome or a metric
 * reads. Kept in memory for a few minutes per plan as it stands: any change to
 * the draft is another key.
 */
@Component
public class DraftRiskQuery {
  static final Duration KEPT = Duration.ofMinutes(10);
  static final int MOST_KEPT = 64;

  private record Kept(PlanPredictionsView view, Instant at) {}

  private final PlanScoringJob scoring;
  private final PlanQuery plans;
  private final Database database;
  private final Clock clock;
  private final Map<String, Kept> kept = new ConcurrentHashMap<>();

  public DraftRiskQuery(PlanScoringJob scoring, PlanQuery plans, Database database, Clock clock) {
    this.scoring = scoring;
    this.plans = plans;
    this.database = database;
    this.clock = clock;
  }

  public PlanPredictionsView risk(Actor actor, UUID planId) {
    PlanView plan = database.asSystem(ModuleRole.ML, () -> plans.plan(planId))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    // The reader must hold the plan's depot; outside it is refused, never answered as nothing.
    Map<String, Object> scope = database.readAs(ModuleRole.ML, actor.userId(),
        () -> database.queryOne("SELECT app.actor_has_depot(?) AS ok", plan.depotCode()));
    if (!Boolean.TRUE.equals(scope.get("ok"))) {
      throw new DomainException(ErrorCode.FORBIDDEN, "plan " + planId + " is outside the actor's scope", List.of("R-ML-48"));
    }
    Instant now = clock.now();
    String key = key(plan);
    Kept had = kept.get(key);
    if (had != null && had.at().plus(KEPT).isAfter(now)) {
      return had.view();
    }
    PlanScoringJob.Preview p = scoring.preview(planId)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    PlanPredictionsView view = new PlanPredictionsView(
        new PlanScoringView(planId, plan.depotCode(), plan.serviceDate(), p.scored() ? ScoringStatus.SCORED : ScoringStatus.DEGRADED,
            Optional.of(p.modelLabel()), Optional.empty(), p.reason(), Optional.of(now)),
        p.stops(), Optional.empty());
    if (kept.size() >= MOST_KEPT) {
      kept.entrySet().removeIf(e -> !e.getValue().at().plus(KEPT).isAfter(now));
      if (kept.size() >= MOST_KEPT) kept.clear();
    }
    kept.put(key, new Kept(view, now));
    return view;
  }

  /** The plan as it stands: its version and every trip's vehicle and stops in order. */
  private static String key(PlanView plan) {
    StringBuilder b = new StringBuilder(plan.planId() + ":" + plan.planVersion());
    plan.trips().forEach(t -> {
      b.append('|').append(t.tripId()).append(',').append(t.vehicleId());
      t.stops().forEach(s -> b.append(',').append(s.orderId()).append('@').append(s.sequence()).append('@').append(s.plannedArrival()));
    });
    return Integer.toHexString(b.toString().hashCode()) + ":" + b.length() + ":" + plan.planId();
  }
}
