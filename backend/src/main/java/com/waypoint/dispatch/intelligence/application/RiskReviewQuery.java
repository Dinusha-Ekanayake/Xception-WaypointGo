package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.RiskReviewViews.ReviewView;
import com.waypoint.dispatch.intelligence.contract.RiskReviewViews.RiskReviewReport;
import com.waypoint.dispatch.intelligence.contract.RiskReviewViews.RiskStopView;
import com.waypoint.dispatch.intelligence.domain.RiskReview;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcRiskReviews;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.platform.config.RiskReviewProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Roadmap T1-01: the stops of a draft likely to be late, each with the
 * dispatcher's latest choice. Read as the dispatcher, so a plan of another depot
 * is not found, and computed on request from the deterministic estimate: code,
 * no model call, so a draft can be reviewed as it is edited.
 */
@Component
public class RiskReviewQuery {
  private final Database database;
  private final PlanQuery plans;
  private final JdbcOutcomes outcomes;
  private final JdbcRiskReviews reviews;
  private final RiskReviewProperties properties;

  public RiskReviewQuery(
      Database database, PlanQuery plans, JdbcOutcomes outcomes, JdbcRiskReviews reviews, RiskReviewProperties properties) {
    this.database = database;
    this.plans = plans;
    this.outcomes = outcomes;
    this.reviews = reviews;
    this.properties = properties;
  }

  public RiskReviewReport report(Actor actor, UUID planId) {
    if (!properties.enabled()) {
      return new RiskReviewReport(planId, false, true, RiskReview.THRESHOLD_PERCENT, List.of());
    }
    return database.readAs(ModuleRole.ML, actor.userId(), () -> {
      PlanView plan = plans.plan(planId)
          .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId + " within your scope"));
      Map<UUID, ReviewView> latest = reviews.latest(planId);
      List<RiskStopView> stops = flagged(plan).stream()
          .map(f -> new RiskStopView(f.orderId(), f.tripId(), f.vehicleId(), f.sequence(), f.outletId(), f.percent(),
              f.minutesToClose(), f.stopOfTrip(), Optional.ofNullable(latest.get(f.orderId()))))
          .toList();
      return new RiskReviewReport(planId, true, true, RiskReview.THRESHOLD_PERCENT, stops);
    });
  }

  /** The plan's stops at risk, from the estimate tuned on Waypoint's own outcomes (R-ML-14). */
  List<RiskReview.Flagged> flagged(PlanView plan) {
    List<RiskReview.PlannedStop> stops = new ArrayList<>();
    for (TripView trip : plan.trips()) {
      int index = 0;
      for (StopView s : trip.stops().stream().sorted(Comparator.comparingInt(StopView::sequence)).toList()) {
        stops.add(new RiskReview.PlannedStop(s.orderId(), trip.tripId(), trip.vehicleId(), s.sequence(), index++,
            s.outletId(), s.plannedArrival(), s.windowClose()));
      }
    }
    return RiskReview.flag(stops, outcomes.currentTuning());
  }

  Optional<PlanView> plan(UUID planId) {
    return plans.plan(planId);
  }
}
