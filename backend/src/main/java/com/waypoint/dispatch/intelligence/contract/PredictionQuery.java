package com.waypoint.dispatch.intelligence.contract;

import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanPredictionsView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanScoringView;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.SupplyProbabilityView;
import com.waypoint.dispatch.intelligence.contract.TravelAndServiceEstimator.DemandForecast;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored predictions, read without calling any model. Reads run as the asking
 * actor, so row-level security limits them to the actor's depots.
 */
public interface PredictionQuery {

  /** Empty until the plan has been published. */
  Optional<PlanScoringView> planScoring(UUID planId);

  /** The model's predictions when the plan was scored by one, else the deterministic ones. */
  Optional<PlanPredictionsView> predictionsFor(UUID planId);

  /** The newest forecast per week, ISO weeks from {@code fromWeek} to {@code toWeek} inclusive. */
  List<DemandForecast> forecast(
      String depotCode, String brandCode, int fromYear, int fromWeek, int toYear, int toWeek);

  /** R-RCP-06. Empty when the order does not exist or is not the actor's to see. */
  Optional<SupplyProbabilityView> supplyProbability(UUID orderId);
}
