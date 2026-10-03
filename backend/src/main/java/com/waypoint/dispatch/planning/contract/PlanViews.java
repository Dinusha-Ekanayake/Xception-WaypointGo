package com.waypoint.dispatch.planning.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules and the dispatcher see of a plan.
 *
 * <p>Every allocation carries the constraint results that produced it, because
 * the constraint registry has four consumers (engine, override path, publication
 * gate, UI) and the UI is one of them: explainability is structural, not a
 * feature (MODULES §4).
 */
public final class PlanViews {
  private PlanViews() {}

  public enum PlanStatus {
    DRAFT,
    PUBLISHED,
    /** Replaced by a newer published version; kept, never edited (R-PLN-28). */
    SUPERSEDED,
    CANCELLED
  }

  public enum AllocationDecision {
    SERVED,
    DEFERRED,
    /** Larger than any eligible vehicle (R-PLN-22). Needs a person, not another run. */
    UNSERVABLE
  }

  /**
   * One planning run for a depot and service day.
   *
   * @param planVersion the business revision; {@code rowVersion} is the
   *     concurrency revision. They are different things (AGENTS.md)
   * @param savedAt when this version was written: for a draft, its last edit
   * @param referenceVersionId the reference snapshot the plan was built on
   * @param ruleSetVersionId the effective rule parameters
   * @param priorityPolicyVersionId the deferral priority table in force
   * @param supersedes the published plan this version replaced
   */
  public record PlanView(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      PlanStatus status,
      UUID referenceVersionId,
      UUID ruleSetVersionId,
      UUID priorityPolicyVersionId,
      Optional<UUID> supersedes,
      Optional<Instant> publishedAt,
      Instant savedAt,
      boolean plannedWithoutPredictor,
      List<TripView> trips,
      List<AllocationView> allocations,
      long rowVersion,
      String engine,
      Optional<ImprovementView> improvement) {

    public PlanView {
      trips = List.copyOf(trips);
      allocations = List.copyOf(allocations);
    }
  }

  /**
   * One trip: one vehicle, one brand, one district, one temperature class
   * (R-PLN-01, decision D-J).
   *
   * @param tripNumber 1 or 2 (R-PLN-07)
   * @param plannedMinutes trip time by the published formula, no return leg (R-PLN-08)
   */
  public record TripView(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      String brandCode,
      String districtName,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      BigDecimal plannedMinutes,
      LocalTime plannedDeparture,
      List<StopView> stops) {

    public TripView {
      stops = List.copyOf(stops);
    }
  }

  /** A stop in delivery order. Loading reverses this order (decision D-L). */
  public record StopView(
      int sequence,
      UUID orderId,
      String outletId,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      BigDecimal serviceMinutes) {}

  /**
   * One place an order could take in its open draft: the vehicle and trip an
   * override would name, and what every rule says about it.
   *
   * @param joins true when the trip already exists; false when the order would open it
   * @param bindingRule the first rule that refuses this place; empty when it is feasible
   */
  public record PlacementView(
      String vehicleId,
      int tripNumber,
      boolean joins,
      Optional<UUID> tripId,
      boolean feasible,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResultView> checks) {

    public PlacementView {
      checks = List.copyOf(checks);
    }
  }

  /** Who decided where an order stands (rule 8). */
  public enum AllocationSource {
    /** Nobody: the engine's own answer. */
    ENGINE,
    /** A dispatcher placed it on a trip by hand. */
    OVERRIDE,
    /** A dispatcher traded it for another order on a trip. */
    SWAP,
    /** A dispatcher decided it stays deferred. */
    KEPT,
    /** A dispatcher took it off its trip. */
    MANUAL_DEFER,
    /** Restored from a saved plan. */
    RESTORED
  }

  /**
   * @param bindingRule the rule that decided a deferral or an unservable order;
   *     never a generic message (R-PLN-19)
   * @param checks every constraint evaluated, with its slack
   * @param source who decided it; {@code ENGINE} when nobody did
   * @param locked held on its trip, so a regenerate keeps it there
   * @param decidedBy the dispatcher behind a hand decision or a lock
   * @param lastServedOn the outlet's most recent day a published plan served an
   *     order of it, before this plan's day; empty when it has never been served
   */
  public record AllocationView(
      UUID orderId,
      AllocationDecision decision,
      Optional<UUID> tripId,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResultView> checks,
      AllocationSource source,
      boolean locked,
      Optional<UUID> decidedBy,
      Optional<Instant> decidedAt,
      Optional<LocalDate> lastServedOn) {

    public AllocationView {
      checks = List.copyOf(checks);
    }
  }

  /**
   * A plan without its allocations (issue #177): the header, how many orders
   * each decision took, and the trips without their stops. Small for any depot,
   * so a client that cannot hold a whole plan reads this and then pages
   * {@link AllocationPageView}.
   */
  public record PlanSummaryView(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      PlanStatus status,
      UUID referenceVersionId,
      UUID ruleSetVersionId,
      UUID priorityPolicyVersionId,
      Optional<UUID> supersedes,
      Optional<Instant> publishedAt,
      long rowVersion,
      int served,
      int deferred,
      int unservable,
      List<TripSummaryView> trips) {

    public PlanSummaryView {
      trips = List.copyOf(trips);
    }
  }

  public record TripSummaryView(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      String brandCode,
      String districtName,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      BigDecimal plannedMinutes,
      LocalTime plannedDeparture,
      int stopCount) {}

  /** One allocation with where it stops, for a paged read. Empty stop fields for an order not served. */
  public record AllocationLineView(
      UUID orderId,
      AllocationDecision decision,
      Optional<UUID> tripId,
      Optional<Integer> stopSequence,
      Optional<LocalTime> plannedArrival,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResultView> checks) {

    public AllocationLineView {
      checks = List.copyOf(checks);
    }
  }

  /** A keyset page of one plan's allocations in order id order; {@code nextCursor} is empty on the last page. */
  public record AllocationPageView(
      UUID planId, int planVersion, List<AllocationLineView> items, Optional<String> nextCursor) {

    public AllocationPageView {
      items = List.copyOf(items);
    }
  }

  /**
   * What the engine's second pass achieved over its first (issue #92): the
   * reefers planned again as a whole, kept only when better by rank (R-PLN-32).
   *
   * @param improved false when the first plan was already the best the pass found
   * @param stoppedBy {@code NONE}, or {@code NODES} or {@code CLOCK} when the
   *     search stopped before finishing and kept the best it had (rule 9)
   * @param chilledSearched of {@code chilledCandidates}, how many the search
   *     ranked; the rest were placed by insertion (rule 9)
   */
  public record ImprovementView(
      int firstPassServed,
      int firstPassDeferred,
      int served,
      int deferred,
      boolean improved,
      BigDecimal chilledVolumeGainedM3,
      String stoppedBy,
      int chilledCandidates,
      int chilledSearched) {}

  /**
   * @param ruleId an identifier from RULES-AND-POLICIES, for example R-PLN-06
   * @param slack remaining headroom in the rule's unit, when the rule has one
   */
  public record ConstraintResultView(
      String ruleId, boolean passed, String reason, Optional<BigDecimal> slack) {}

  /** @param skipCount how many runs this outlet's order has been skipped (R-PLN-20) */
  public record DeferralView(
      UUID orderId,
      String outletId,
      LocalDate serviceDate,
      String ruleId,
      String reason,
      int skipCount) {}

  /** Weekly fuel, Monday to Sunday, including return legs (R-PLN-23, decision D-K). */
  public record FuelView(
      String vehicleId,
      LocalDate weekStarting,
      BigDecimal quotaLitres,
      BigDecimal usedLitres,
      BigDecimal remainingLitres) {}

  /** Where a saved plan came from. */
  public enum SnapshotKind {
    /** The engine's plan of a generate. */
    AUTO,
    /** Saved by a dispatcher. */
    MANUAL,
    /** The draft a regenerate replaced. */
    REGENERATED
  }

  /** A saved plan's header: enough to list it. */
  public record SnapshotView(
      UUID snapshotId,
      String depotCode,
      LocalDate serviceDate,
      int number,
      String label,
      SnapshotKind kind,
      UUID sourcePlanId,
      int planVersion,
      UUID createdBy,
      Instant createdAt) {}

  /** How an order's place differs between two plans. */
  public enum ChangeKind {
    /** Served by both, on another vehicle or trip. */
    MOVED,
    /** Served by the second, not by the first. */
    ADDED,
    /** Served by the first, not by the second. */
    DROPPED
  }

  /** Where an order stands in one plan: absent when the plan does not have it. */
  public record PlaceView(
      Optional<AllocationDecision> decision, Optional<String> vehicleId, Optional<Integer> tripNumber) {}

  public record OrderChange(
      UUID orderId, Optional<String> outletId, ChangeKind kind, PlaceView before, PlaceView after) {}

  /** One side of a comparison: the plan's name and what it adds up to. */
  public record PlanSideView(
      String label,
      UUID planId,
      int planVersion,
      int served,
      int deferred,
      int unservable,
      int trips,
      int vehicles) {}

  /**
   * Two plans of one depot and day, side by side.
   *
   * @param changedTrips trips of {@code b} a driver would see differently from {@code a}
   * @param removedTrips trips of {@code a} that {@code b} no longer has
   * @param affectedOutlets outlets whose order changed place
   */
  public record ComparisonView(
      PlanSideView a,
      PlanSideView b,
      List<OrderChange> changes,
      List<UUID> changedTrips,
      List<UUID> removedTrips,
      List<String> affectedOutlets) {

    public ComparisonView {
      changes = List.copyOf(changes);
      changedTrips = List.copyOf(changedTrips);
      removedTrips = List.copyOf(removedTrips);
      affectedOutlets = List.copyOf(affectedOutlets);
    }
  }

  /** A saved plan with the plan itself, read only. */
  public record SnapshotDetailView(SnapshotView snapshot, PlanView plan) {}

  /**
   * What a swap or a new stop order would leave: the trip as it would run, and
   * every rule's verdict on the vehicle's whole day.
   */
  public record TripPreview(
      String vehicleId,
      int tripNumber,
      boolean feasible,
      List<StopView> stops,
      List<ConstraintResultView> checks) {

    public TripPreview {
      stops = List.copyOf(stops);
      checks = List.copyOf(checks);
    }
  }

  /** Whether a substitute vehicle could carry a trip, checked against the whole registry. */
  public record InterchangePreview(
      UUID tripId,
      String currentVehicleId,
      String replacementVehicleId,
      boolean feasible,
      List<ConstraintResultView> checks) {

    public InterchangePreview {
      checks = List.copyOf(checks);
    }
  }
}
