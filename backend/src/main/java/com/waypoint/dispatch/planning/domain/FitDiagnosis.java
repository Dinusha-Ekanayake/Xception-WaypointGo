package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.domain.PlanningRun.Placement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Why a deferred order cannot go, and where it comes closest (roadmap AI-2).
 * The places are the engine's own (every vehicle's trips and a new trip, each
 * checked against the whole registry); this only orders them. Pure.
 */
public final class FitDiagnosis {
  private FitDiagnosis() {}

  /** The diagnosis: whether any place fits, how many were checked, the closest one, and the swap if one works. */
  public record Diagnosis(boolean fits, int placesChecked, Optional<Placement> closest, Optional<PlanOrder> swapOut) {}

  /**
   * Checks every place for {@code order} on the run, and when none fits and the
   * order is deferred, tries the lower-ranked orders on the closest trip as
   * swaps through the engine's own swap check, lowest-ranked first.
   */
  public static Diagnosis diagnose(PlanningRun run, PlanOrder order, List<FleetVehicle> fleet,
      ConstraintRegistry registry, PlanContext context, Comparator<PlanOrder> rank) {
    List<Placement> places = run.options(order, fleet, registry, context);
    boolean fits = places.stream().anyMatch(Placement::feasible);
    Optional<Placement> closest = closest(places);
    boolean deferred = run.decisionFor(order.orderId())
        .map(d -> d.decision() == com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.DEFERRED)
        .orElse(false);
    Optional<PlanOrder> swap = Optional.empty();
    if (!fits && deferred && closest.isPresent() && closest.get().joins()) {
      Placement at = closest.get();
      Optional<Trip> trip = run.days().stream()
          .filter(d -> d.vehicleId().equals(at.vehicleId()))
          .findFirst()
          .filter(d -> at.tripNumber() <= d.trips().size())
          .map(d -> d.trips().get(at.tripNumber() - 1));
      for (PlanOrder out : trip.map(t -> swapCandidates(t, order, rank)).orElse(List.of())) {
        boolean works;
        try {
          works = run.proposeSwap(out, order, registry, context).feasible();
        } catch (com.waypoint.dispatch.shared.error.DomainException e) {
          works = false;
        }
        if (works) {
          swap = Optional.of(out);
          break;
        }
      }
    }
    return new Diagnosis(fits, places.size(), closest, swap);
  }

  /**
   * Rules no amount of room can fix at that place: the trip's brand and
   * district, its temperature, the vehicle's access, depot and availability.
   * A place that breaks one of them is not close, however few rules it breaks.
   */
  static final java.util.Set<String> STRUCTURAL =
      java.util.Set.of("R-PLN-01", "R-PLN-02", "R-PLN-03", "R-PLN-04", "R-PLN-31", "R-FLT-03");

  static boolean structural(Placement p) {
    return p.checks().stream().anyMatch(c -> !c.passed() && STRUCTURAL.contains(c.ruleId()));
  }

  /**
   * The closest place: one whose only failures are room or time (capacity,
   * trips, Fresh minutes, windows, fuel) before one that breaks a structural
   * rule; then fewest failed rules; then joining a trip before a new one; then
   * vehicle and trip order.
   */
  public static Optional<Placement> closest(List<Placement> places) {
    return places.stream()
        .min(Comparator.comparing(FitDiagnosis::structural)
            .thenComparingLong((Placement p) -> ConstraintRegistry.failures(p.checks()))
            .thenComparing(p -> !p.joins())
            .thenComparing(Placement::vehicleId)
            .thenComparingInt(Placement::tripNumber));
  }

  /** The rules a place breaks, in registry order. */
  public static List<ConstraintResult> failed(Placement place) {
    return place.checks().stream().filter(c -> !c.passed()).toList();
  }

  /**
   * The served orders on a trip that may make room for {@code deferred}: those
   * that rank below it under the plan's policy, lowest-ranked first, so the
   * first that works costs the least by rank.
   */
  public static List<PlanOrder> swapCandidates(Trip trip, PlanOrder deferred, Comparator<PlanOrder> rank) {
    return trip.orders().stream()
        .filter(o -> rank.compare(o, deferred) > 0)
        .sorted(rank.reversed())
        .toList();
  }
}
