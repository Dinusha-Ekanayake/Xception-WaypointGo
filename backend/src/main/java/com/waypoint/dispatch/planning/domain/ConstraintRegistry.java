package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.util.List;
import java.util.Optional;

/**
 * The single list of planning constraints. Order matters only for explanation:
 * when several rules fail, the first one in this order is reported as binding,
 * so the structural rules (availability, depot, brand) come before the
 * quantitative ones (capacity, time, fuel).
 */
public final class ConstraintRegistry {
  private final List<Constraint> constraints;

  public ConstraintRegistry(List<Constraint> constraints) {
    this.constraints = List.copyOf(constraints);
  }

  public static ConstraintRegistry standard() {
    return new ConstraintRegistry(
        List.of(
            new Constraints.VehicleAvailable(),
            new Constraints.HomeDepot(),
            new Constraints.SingleBrandDistrict(),
            new Constraints.SingleTemperaturePerTrip(),
            new Constraints.Temperature(),
            new Constraints.VanOnlyAccess(),
            new Constraints.WholeOrder(),
            new Constraints.WeightCapacity(),
            new Constraints.VolumeCapacity(),
            new Constraints.TripCount(),
            new Constraints.TimeBudget(),
            new Constraints.DeliveryWindow(),
            new Constraints.FuelQuota()));
  }

  public List<Constraint> constraints() {
    return constraints;
  }

  public List<ConstraintResult> evaluate(Candidate candidate) {
    return constraints.stream().map(c -> c.check(candidate)).toList();
  }

  public static boolean allPass(List<ConstraintResult> results) {
    return results.stream().allMatch(ConstraintResult::passed);
  }

  public static Optional<ConstraintResult> firstFailure(List<ConstraintResult> results) {
    return results.stream().filter(r -> !r.passed()).findFirst();
  }

  public static long failures(List<ConstraintResult> results) {
    return results.stream().filter(r -> !r.passed()).count();
  }
}
