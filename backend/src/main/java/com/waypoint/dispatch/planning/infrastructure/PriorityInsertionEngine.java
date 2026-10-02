package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.TemperatureClass;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The first engine: deterministic priority insertion (decision 1 of the issue
 * #9 plan).
 *
 * <ol>
 *   <li><b>Screen.</b> An order no vehicle of the depot could carry even empty,
 *       free and fully fuelled is {@code UNSERVABLE} (R-PLN-22, PLN-02, PLN-09),
 *       never a deferral that repeats forever.
 *   <li><b>Insert in priority order.</b> Each order goes to the cheapest feasible
 *       place: join an open trip before opening one, keep reefers and vans for
 *       the orders only they can carry, then best fit on volume.
 *   <li><b>Explain.</b> An order with no feasible place is deferred with the
 *       first failing rule of the candidate that came closest.
 * </ol>
 *
 * <p>An improvement search or an optimiser can replace this behind
 * {@link AllocationEngine}; {@link ValidatingEngine} keeps any replacement honest.
 */
public final class PriorityInsertionEngine implements AllocationEngine {
  public static final String NAME = "priority-insertion-v1";
  public static final String TIMEOUT_RULE = "R-PLN-ENGINE-TIMEOUT";

  private final ConstraintRegistry registry;
  private final LongSupplier nanoTime;

  public PriorityInsertionEngine(ConstraintRegistry registry) {
    this(registry, System::nanoTime);
  }

  PriorityInsertionEngine(ConstraintRegistry registry, LongSupplier nanoTime) {
    this.registry = registry;
    this.nanoTime = nanoTime;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public AllocationResult allocate(Problem problem) {
    PlanContext ctx = problem.context();
    long deadline = nanoTime.getAsLong() + problem.rules().engineBudgetMillis() * 1_000_000L;

    Map<String, VehicleDay> days = new LinkedHashMap<>();
    problem.fleet().stream()
        .filter(FleetVehicle::available)
        .sorted(Comparator.comparing(FleetVehicle::vehicleId))
        .forEach(v -> days.put(v.vehicleId(), VehicleDay.idle(v)));

    List<OrderDecision> decisions = new ArrayList<>();
    List<PlanOrder> servable = new ArrayList<>();
    for (PlanOrder o : problem.orders()) {
      Optional<OrderDecision> unservable = screen(o, problem, ctx);
      if (unservable.isPresent()) {
        decisions.add(unservable.get());
      } else {
        servable.add(o);
      }
    }

    boolean partial = false;
    Map<UUID, PlanOrder> placed = new LinkedHashMap<>();
    for (PlanOrder o : problem.policy().rank(servable, ctx)) {
      if (nanoTime.getAsLong() > deadline) {
        partial = true;
        decisions.add(deferred(o, TIMEOUT_RULE, "the engine reached its time budget before reaching this order", List.of()));
        continue;
      }
      Placement best = null;
      Closest closest = null;
      for (VehicleDay day : days.values()) {
        for (Option option : options(day, o, ctx.rules().maxTrips())) {
          List<ConstraintResult> results = registry.evaluate(new Candidate(option.day(), ctx, Set.of()));
          if (ConstraintRegistry.allPass(results)) {
            Placement p = new Placement(option.day(), cost(option, o));
            if (best == null || p.cost().compareTo(best.cost()) < 0) {
              best = p;
            }
          } else {
            Closest c = Closest.of(results);
            if (c.closerThan(closest)) {
              closest = c;
            }
          }
        }
      }
      if (best != null) {
        days.put(best.day().vehicleId(), best.day());
        placed.put(o.orderId(), o);
      } else {
        decisions.add(deferredFrom(o, closest));
      }
    }

    for (PlanOrder o : placed.values()) {
      decisions.add(served(o, days, ctx));
    }
    decisions.sort(Comparator.comparing(OrderDecision::orderId));
    return new AllocationResult(List.copyOf(days.values()), decisions, partial, NAME);
  }

  private Optional<OrderDecision> screen(PlanOrder o, Problem problem, PlanContext ctx) {
    if (!ctx.travel().containsKey(o.district())) {
      return Optional.of(
          new OrderDecision(o.orderId(), AllocationDecision.UNSERVABLE, Optional.empty(), Optional.empty(),
              Optional.of("R-PLN-12"), "no travel profile for district " + o.district(), List.of()));
    }
    Closest closest = null;
    for (FleetVehicle v : problem.fleet()) {
      List<ConstraintResult> results =
          registry.evaluate(new Candidate(VehicleDay.idle(v.asIdeal()).withNewTrip(o), ctx, Set.of()));
      if (ConstraintRegistry.allPass(results)) {
        return Optional.empty();
      }
      Closest c = Closest.of(results);
      if (c.closerThan(closest)) {
        closest = c;
      }
    }
    if (closest == null) {
      return Optional.of(
          new OrderDecision(o.orderId(), AllocationDecision.UNSERVABLE, Optional.empty(), Optional.empty(),
              Optional.of("R-PLN-04"), "the depot has no vehicles", List.of()));
    }
    ConstraintResult binding = ConstraintRegistry.firstFailure(closest.results()).orElseThrow();
    return Optional.of(
        new OrderDecision(o.orderId(), AllocationDecision.UNSERVABLE, Optional.empty(), Optional.empty(),
            Optional.of(binding.ruleId()), "no vehicle of the depot could ever carry it: " + binding.reason(), closest.results()));
  }

  private record Option(VehicleDay day, boolean joins, Trip trip) {}

  private record Placement(VehicleDay day, Cost cost) {}

  /**
   * Rules that say "this vehicle is the wrong kind", as opposed to "this
   * vehicle is full or out of time". A deferral is explained by the closest
   * candidate of the right kind, so a chilled order squeezed out of full reefers
   * reports the reefer's capacity, not that some ambient truck is not a reefer.
   */
  private static final Set<String> STRUCTURAL =
      Set.of("R-FLT-03", "R-PLN-04", "R-PLN-01", "R-PLN-31", "R-PLN-02", "R-PLN-03", "R-PLN-05");

  private record Closest(long structural, long failures, List<ConstraintResult> results) {
    static Closest of(List<ConstraintResult> results) {
      long structural = results.stream().filter(r -> !r.passed() && STRUCTURAL.contains(r.ruleId())).count();
      return new Closest(structural, ConstraintRegistry.failures(results), results);
    }

    boolean closerThan(Closest other) {
      return other == null
          || structural < other.structural
          || (structural == other.structural && failures < other.failures);
    }
  }

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

  private static List<Option> options(VehicleDay day, PlanOrder o, int maxTrips) {
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

  private OrderDecision served(PlanOrder o, Map<String, VehicleDay> days, PlanContext ctx) {
    for (VehicleDay day : days.values()) {
      Optional<Integer> trip = day.tripNumberOf(o.orderId());
      if (trip.isPresent()) {
        List<ConstraintResult> checks = registry.evaluate(new Candidate(day, ctx, Set.of()));
        return new OrderDecision(o.orderId(), AllocationDecision.SERVED, Optional.of(day.vehicleId()), trip,
            Optional.empty(), "served on " + day.vehicleId() + " trip " + trip.get(), checks);
      }
    }
    throw new IllegalStateException("placed order " + o.orderRef() + " is on no vehicle");
  }

  private static OrderDecision deferredFrom(PlanOrder o, Closest closest) {
    if (closest == null) {
      return deferred(o, "R-FLT-03", "no vehicle is available at the depot that day", List.of());
    }
    ConstraintResult binding = ConstraintRegistry.firstFailure(closest.results()).orElseThrow();
    return deferred(o, binding.ruleId(), binding.reason(), closest.results());
  }

  private static OrderDecision deferred(PlanOrder o, String rule, String reason, List<ConstraintResult> checks) {
    return new OrderDecision(o.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
        Optional.of(rule), reason, checks);
  }
}
