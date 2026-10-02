package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
   */
  record Problem(
      String depotCode,
      LocalDate serviceDate,
      List<PlanOrder> orders,
      List<FleetVehicle> fleet,
      Map<String, DistrictTravel> travel,
      RuleSet rules,
      PriorityPolicy policy) {

    public Problem {
      orders = List.copyOf(orders);
      fleet = List.copyOf(fleet);
      travel = Map.copyOf(travel);
    }

    public PlanContext context() {
      return new PlanContext(depotCode, travel, rules);
    }
  }

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
   */
  record AllocationResult(
      List<VehicleDay> days,
      List<OrderDecision> decisions,
      boolean partial,
      String engine,
      Optional<ScarceFleetReplan.Summary> improvement) {

    public AllocationResult {
      days = days.stream().filter(d -> !d.trips().isEmpty()).toList();
      decisions = List.copyOf(decisions);
    }

    public AllocationResult(List<VehicleDay> days, List<OrderDecision> decisions, boolean partial, String engine) {
      this(days, decisions, partial, engine, Optional.empty());
    }

    public Optional<OrderDecision> decisionFor(UUID orderId) {
      return decisions.stream().filter(d -> d.orderId().equals(orderId)).findFirst();
    }
  }
}
