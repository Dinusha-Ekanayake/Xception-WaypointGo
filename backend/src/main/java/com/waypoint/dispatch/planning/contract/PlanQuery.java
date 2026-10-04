package com.waypoint.dispatch.planning.contract;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.DeferralView;
import com.waypoint.dispatch.planning.contract.PlanViews.FuelView;
import com.waypoint.dispatch.planning.contract.PlanViews.InterchangePreview;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only way another module reads plans. */
public interface PlanQuery {

  /** Effective trip ceiling for a service date, shared with fleet forecasts. */
  int maxTripsFor(LocalDate serviceDate);

  /** The current published version, never a draft or a superseded one. */
  Optional<PlanView> publishedPlan(String depotCode, LocalDate serviceDate);

  Optional<PlanView> draft(UUID planId);

  /** A plan in any status, by id (issue #16: scoring a published plan). */
  Optional<PlanView> plan(UUID planId);

  /** Where an order could go in the current draft, each option with its constraint results. */
  List<AllocationView> previewAssignments(UUID orderId);

  /**
   * Synchronous revalidation for Loading: could this vehicle take this trip?
   * Read only. Only a Planning command changes a published plan (decision B11).
   */
  InterchangePreview previewInterchange(UUID tripId, String replacementVehicleId);

  List<DeferralView> deferralsFor(String depotCode, LocalDate serviceDate);

  Optional<FuelView> fuelRemaining(String vehicleId, LocalDate anyDayOfWeek);
}
