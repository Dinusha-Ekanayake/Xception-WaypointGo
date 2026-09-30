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
      long rowVersion) {

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
