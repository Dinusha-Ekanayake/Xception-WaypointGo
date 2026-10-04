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
  public static final String SWAP = "plan:Swap";
  public static final String KEEP_DEFERRED = "plan:KeepDeferred";
  public static final String LOCK = "plan:Lock";
  public static final String UNLOCK = "plan:Unlock";
  public static final String REORDER_STOPS = "plan:ReorderStops";
  public static final String CONTACT_STORE = "plan:ContactStore";
  public static final String EDIT_TRIP = "plan:EditTrip";
  public static final String SAVE_SNAPSHOT = "plan:SaveSnapshot";
  public static final String RESTORE_SNAPSHOT = "plan:RestoreSnapshot";

  /**
   * @param keepDecisions put back what the dispatcher placed, locked or kept
   *     deferred in the open draft, and place the rest again; absent means false
   */
  public record GenerateDraft(String depotCode, LocalDate serviceDate, Boolean keepDecisions) {}

  /**
   * Move an order to a vehicle and trip in a draft. Revalidated against the
   * registry; a violation is returned, never silently accepted.
   */
  public record OverrideAllocation(
      UUID planId, UUID orderId, String vehicleId, int tripNumber, String reason) {}

  public record DeferOrder(UUID planId, UUID orderId, String reason) {}

  /**
   * Trade a served order for a deferred one in a draft: {@code outOrderId} is
   * deferred and {@code inOrderId} takes its place on the trip, or nothing changes.
   * {@code orderIds} is optional: the trip's stop order after the swap, every
   * order of it once, when the dispatcher fixed one in the same window.
   */
  public record SwapOrders(UUID planId, UUID outOrderId, UUID inOrderId, String reason, java.util.List<UUID> orderIds) {}

  /** Decide that deferred orders stay deferred, so the plan can be published. */
  public record KeepDeferred(UUID planId, java.util.List<UUID> orderIds, String reason) {}

  /** Hold a served order on its trip ({@link #LOCK}) or let it go ({@link #UNLOCK}). */
  public record LockOrder(UUID planId, UUID orderId) {}

  /** Fix the order of one trip's stops; every order of the trip exactly once. */
  public record ReorderStops(UUID planId, UUID tripId, java.util.List<UUID> orderIds, String reason) {}

  /**
   * Make one trip hold exactly {@code orderIds}, in that order: orders left out
   * are deferred, orders named from the deferred list or another trip join it,
   * and an empty list removes the trip. Judged whole (R-PLN-42).
   */
  public record EditTrip(UUID planId, UUID tripId, java.util.List<UUID> orderIds, String reason) {}

  /** Tell the order's store manager the plan could not serve it. Changes no plan. */
  public record ContactStore(UUID planId, UUID orderId, String message) {}

  /** Save the draft as the dispatcher sees it; the label defaults to a numbered name. */
  public record SaveSnapshot(UUID planId, Optional<String> label) {}

  /**
   * Return the open draft to a saved plan: its placements go back, locked and
   * kept orders as they were, and the engine places what arrived since.
   */
  public record RestoreSnapshot(UUID planId, UUID snapshotId) {}

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
