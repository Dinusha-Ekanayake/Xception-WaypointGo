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

  /**
   * @param bindingRule the rule that decided a deferral or an unservable order;
   *     never a generic message (R-PLN-19)
   * @param checks every constraint evaluated, with its slack
   */
  public record AllocationView(
      UUID orderId,
      AllocationDecision decision,
      Optional<UUID> tripId,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResultView> checks) {

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
