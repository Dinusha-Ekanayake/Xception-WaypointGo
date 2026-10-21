package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The allocation algorithm, behind a port so a better one replaces it without
 * touching the rules: whatever an engine returns is re-checked against the
 * same {@link ConstraintRegistry} before it leaves the module (PLN-12).
 */
public interface AllocationEngine {

  AllocationResult allocate(Problem problem);

  String name();

  /**
   * One depot and service day.
   *
   * @param fleet every vehicle of the depot, available or not: the unservable
   *     screen asks whether any vehicle could ever carry an order (R-PLN-22)
   * @param pins orders a dispatcher placed or swapped by hand, which a regenerate
   *     puts back where they were before it places anything else (R-PLN-36)
   * @param held orders a dispatcher decided stay deferred; they are not
   *     offered a place
   * @param mustPlace orders a dispatcher locked: they must go out in this plan,
   *     on any vehicle that passes every rule, placed right after the pins; one
   *     that fits nowhere refuses the run (R-PLN-35, R-PLN-36)
   */
  record Problem(
      String depotCode,
      LocalDate serviceDate,
      List<PlanOrder> orders,
      List<FleetVehicle> fleet,
      Map<String, DistrictTravel> travel,
      RuleSet rules,
      PriorityPolicy policy,
      Map<UUID, Pin> pins,
      Set<UUID> held,
      Set<UUID> mustPlace) {

    public Problem {
      orders = List.copyOf(orders);
      fleet = List.copyOf(fleet);
      travel = Map.copyOf(travel);
      pins = Map.copyOf(pins);
      held = Set.copyOf(held);
      mustPlace = Set.copyOf(mustPlace);
    }

    public Problem(
        String depotCode,
        LocalDate serviceDate,
        List<PlanOrder> orders,
        List<FleetVehicle> fleet,
        Map<String, DistrictTravel> travel,
        RuleSet rules,
        PriorityPolicy policy,
        Map<UUID, Pin> pins,
        Set<UUID> held) {
      this(depotCode, serviceDate, orders, fleet, travel, rules, policy, pins, held, Set.of());
    }

    /** A problem with nothing pinned and nothing held: the engine decides everything. */
    public Problem(
        String depotCode,
        LocalDate serviceDate,
        List<PlanOrder> orders,
        List<FleetVehicle> fleet,
        Map<String, DistrictTravel> travel,
        RuleSet rules,
        PriorityPolicy policy) {
      this(depotCode, serviceDate, orders, fleet, travel, rules, policy, Map.of(), Set.of(), Set.of());
    }

    /** Any decision of a dispatcher's: the later stages then leave the plan as placed, so none moves or drops it. */
    public boolean hasDecisions() {
      return !pins.isEmpty() || !held.isEmpty() || !mustPlace.isEmpty();
    }

    /** The same problem with a dispatcher's decisions to keep. */
    public Problem keeping(Map<UUID, Pin> nextPins, Set<UUID> nextHeld) {
      return keeping(nextPins, nextHeld, Set.of());
    }

    /** The same problem with a dispatcher's decisions to keep, and the locked orders that must go out. */
    public Problem keeping(Map<UUID, Pin> nextPins, Set<UUID> nextHeld, Set<UUID> nextMustPlace) {
      return new Problem(depotCode, serviceDate, orders, fleet, travel, rules, policy, nextPins, nextHeld, nextMustPlace);
    }

    public PlanContext context() {
      return new PlanContext(depotCode, travel, rules);
    }
  }

  /** Where an order was put: a vehicle and its trip number. */
  record Pin(String vehicleId, int tripNumber) {}

  /**
   * @param bindingRule for a deferral or an unservable order, the rule that
   *     decided it; never a generic message (R-PLN-19)
   * @param checks the constraint results behind the decision, for the UI
   */
  record OrderDecision(
      UUID orderId,
      AllocationDecision decision,
      Optional<String> vehicleId,
      Optional<Integer> tripNumber,
      Optional<String> bindingRule,
      String reason,
      List<ConstraintResult> checks) {

    public OrderDecision {
      checks = List.copyOf(checks);
    }
  }

  /**
   * @param partial the engine ran out of time; the rest were deferred, never dropped (PLN-11)
   * @param improvement what a second pass over the first plan achieved, when one ran (issue #92)
   * @param cost what the cost stage did, or why it did not run (R-PLN-39)
   * @param alternative the rules plan, when the cost stage replaced it, for the dispatcher to compare
   */
  record AllocationResult(
      List<VehicleDay> days,
      List<OrderDecision> decisions,
      boolean partial,
      String engine,
      Optional<ScarceFleetReplan.Summary> improvement,
      Optional<CostReplan.Summary> cost,
      Optional<AllocationResult> alternative) {

    public AllocationResult {
      days = days.stream().filter(d -> !d.trips().isEmpty()).toList();
      decisions = List.copyOf(decisions);
    }

    public AllocationResult(List<VehicleDay> days, List<OrderDecision> decisions, boolean partial, String engine) {
      this(days, decisions, partial, engine, Optional.empty());
    }

    public AllocationResult(
        List<VehicleDay> days, List<OrderDecision> decisions, boolean partial, String engine,
        Optional<ScarceFleetReplan.Summary> improvement) {
      this(days, decisions, partial, engine, improvement, Optional.empty(), Optional.empty());
    }

    public Optional<OrderDecision> decisionFor(UUID orderId) {
      return decisions.stream().filter(d -> d.orderId().equals(orderId)).findFirst();
    }
  }
}
