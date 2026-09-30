package com.waypoint.dispatch.planning.contract;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Payloads of Planning's commands. A published plan is immutable with all its
 * trips and allocations; {@link RevisePlan} and {@link ReplanTrip} create a new
 * version that supersedes it (R-PLN-28, decision B11).
 */
public final class PlanCommands {
  private PlanCommands() {}

  public static final String GENERATE = "plan:Generate";
  public static final String OVERRIDE = "plan:Override";
  public static final String DEFER = "plan:Defer";
  public static final String PUBLISH = "plan:Publish";
  public static final String REVISE = "plan:Revise";
  public static final String REPLAN = "plan:Replan";

  public record GenerateDraft(String depotCode, LocalDate serviceDate) {}

  /**
   * Move an order to a vehicle and trip in a draft. Revalidated against the
   * registry; a violation is returned, never silently accepted.
   */
  public record OverrideAllocation(
      UUID planId, UUID orderId, String vehicleId, int tripNumber, String reason) {}

  public record DeferOrder(UUID planId, UUID orderId, String reason) {}

  /** Refused unless every constraint passes across the whole plan. */
  public record PublishPlan(UUID planId) {}

  /** A new draft version from a published plan. */
  public record RevisePlan(UUID planId, String reason) {}

  /**
   * Replan only the affected trips after a vehicle change, for example an
   * interchange requested by Loading or a vehicle removed after publication.
   */
  public record ReplanTrip(
      UUID planId, UUID tripId, Optional<String> replacementVehicleId, String reason) {}
}
