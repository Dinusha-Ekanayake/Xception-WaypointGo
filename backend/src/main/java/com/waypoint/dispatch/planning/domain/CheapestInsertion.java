package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where one order goes in a set of vehicle days, and why it goes nowhere: the
 * placement and explanation rules of decision 5 of the issue #9 plan, shared by
 * every engine so a deferral is explained the same way whichever pass made it
 * (R-PLN-19).
 *
 * <ul>
 *   <li><b>Place.</b> Every feasible place is costed: joining an open trip
 *       before opening one, keeping reefers and vans for the orders only they
 *       can carry, then best fit on volume. The cheapest wins.
 *   <li><b>Explain.</b> With no feasible place, the candidate that came closest
 *       names the binding rule: the first failure in registry order.
 * </ul>
 */
public final class CheapestInsertion {
  /**
   * Rules that say "this vehicle is the wrong kind", as opposed to "this
   * vehicle is full or out of time". A deferral is explained by the closest
   * candidate of the right kind, so a chilled order squeezed out of full reefers
   * reports the reefer's capacity, not that some ambient truck is not a reefer.
   */
  private static final Set<String> STRUCTURAL =
      Set.of("R-FLT-03", "R-PLN-04", "R-PLN-01", "R-PLN-31", "R-PLN-02", "R-PLN-03", "R-PLN-05");

  private final ConstraintRegistry registry;

  public CheapestInsertion(ConstraintRegistry registry) {
    this.registry = registry;
  }

  /**
   * @param placed the cheapest feasible day for the order, when there is one
   * @param closest the failing candidate that came closest, when nothing fits
   */
  public record Outcome(Optional<VehicleDay> placed, Optional<Closest> closest) {}

  public Outcome place(Collection<VehicleDay> days, PlanOrder order, PlanContext ctx) {
    VehicleDay best = null;
    Cost bestCost = null;
    Closest closest = null;
    for (VehicleDay day : days) {
      for (Option option : options(day, order)) {
        List<ConstraintResult> results = registry.evaluate(new Candidate(option.day(), ctx, Set.of()));
        if (ConstraintRegistry.allPass(results)) {
          Cost cost = cost(option, order);
          if (bestCost == null || cost.compareTo(bestCost) < 0) {
            best = option.day();
            bestCost = cost;
          }
        } else {
          Closest c = Closest.of(results);
          if (c.closerThan(closest)) {
            closest = c;
          }
        }
      }
    }
    return new Outcome(Optional.ofNullable(best), Optional.ofNullable(closest));
  }

  /** Places the order in {@code days} when it fits; otherwise explains the deferral. */
  public Optional<OrderDecision> insertOrDefer(Map<String, VehicleDay> days, PlanOrder order, PlanContext ctx) {
    Outcome outcome = place(days.values(), order, ctx);
    if (outcome.placed().isPresent()) {
      VehicleDay day = outcome.placed().get();
      days.put(day.vehicleId(), day);
      return Optional.empty();
    }
    return Optional.of(deferred(order, outcome.closest()));
  }

  /** The decision for an order carried in {@code days}, with every check of its vehicle's day. */
  public OrderDecision served(PlanOrder order, Collection<VehicleDay> days, PlanContext ctx) {
    for (VehicleDay day : days) {
      Optional<Integer> trip = day.tripNumberOf(order.orderId());
      if (trip.isPresent()) {
        List<ConstraintResult> checks = registry.evaluate(new Candidate(day, ctx, Set.of()));
        return new OrderDecision(order.orderId(), AllocationDecision.SERVED, Optional.of(day.vehicleId()), trip,
            Optional.empty(), "served on " + day.vehicleId() + " trip " + trip.get(), checks);
      }
    }
    throw new IllegalStateException("placed order " + order.orderRef() + " is on no vehicle");
  }

  /** A deferral explained by the closest candidate, or by there being no vehicle at all. */
  public static OrderDecision deferred(PlanOrder order, Optional<Closest> closest) {
    if (closest.isEmpty()) {
      return deferred(order, "R-FLT-03", "no vehicle is available at the depot that day", List.of());
    }
    ConstraintResult binding = ConstraintRegistry.firstFailure(closest.get().results()).orElseThrow();
    return deferred(order, binding.ruleId(), binding.reason(), closest.get().results());
  }

  public static OrderDecision deferred(PlanOrder order, String rule, String reason, List<ConstraintResult> checks) {
    return new OrderDecision(order.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
        Optional.of(rule), reason, checks);
  }

  /** A failing candidate, ranked by how near it came: wrong kind of vehicle first, then failures overall. */
  public record Closest(long structural, long failures, List<ConstraintResult> results) {
    public static Closest of(List<ConstraintResult> results) {
      long structural = results.stream().filter(r -> !r.passed() && STRUCTURAL.contains(r.ruleId())).count();
      return new Closest(structural, ConstraintRegistry.failures(results), results);
    }

    public boolean closerThan(Closest other) {
      return other == null
          || structural < other.structural
          || (structural == other.structural && failures < other.failures);
    }
  }

  private record Option(VehicleDay day, boolean joins, Trip trip) {}

  /** Lower is better, compared field by field. */
  private record Cost(int opensTrip, int scarcity, BigDecimal residualShare) implements Comparable<Cost> {
    @Override
    public int compareTo(Cost other) {
      return Comparator.comparingInt(Cost::opensTrip)
          .thenComparingInt(Cost::scarcity)
          .thenComparing(Cost::residualShare)
          .compare(this, other);
    }
  }

  private static List<Option> options(VehicleDay day, PlanOrder o) {
    List<Option> out = new ArrayList<>();
    for (int n = 1; n <= day.trips().size(); n++) {
      if (day.trip(n).accepts(o)) {
        VehicleDay joined = day.withJoined(n, o);
        out.add(new Option(joined, true, joined.trip(joined.tripNumberOf(o.orderId()).orElseThrow())));
      }
    }
    VehicleDay opened = day.withNewTrip(o);
    out.add(new Option(opened, false, opened.trip(opened.tripNumberOf(o.orderId()).orElseThrow())));
    return out;
  }

  /** Reefers and vans are the scarce vehicles; spend them only on orders that need them. */
  private static Cost cost(Option option, PlanOrder o) {
    FleetVehicle v = option.day().vehicle();
    int scarcity = 0;
    if (v.reefer() && option.trip().temperature() == TemperatureClass.AMBIENT) {
      scarcity++;
    }
    if (v.van() && !o.vanOnly()) {
      scarcity++;
    }
    BigDecimal residual =
        v.volumeCapM3().subtract(option.trip().volumeM3()).divide(v.volumeCapM3(), 6, RoundingMode.HALF_UP);
    return new Cost(option.joins() ? 0 : 1, scarcity, residual);
  }
}
