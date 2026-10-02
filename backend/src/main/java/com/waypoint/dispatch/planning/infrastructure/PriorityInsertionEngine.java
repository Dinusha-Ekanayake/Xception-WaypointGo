package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.CheapestInsertion;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *       place, and an order with no feasible place is deferred with the first
 *       failing rule of the candidate that came closest ({@link CheapestInsertion}).
 * </ol>
 *
 * <p>An improvement search or an optimiser can replace this behind
 * {@link AllocationEngine}; {@link ValidatingEngine} keeps any replacement honest.
 */
public final class PriorityInsertionEngine implements AllocationEngine {
  public static final String NAME = "priority-insertion-v1";
  public static final String TIMEOUT_RULE = "R-PLN-ENGINE-TIMEOUT";

  private final ConstraintRegistry registry;
  private final CheapestInsertion insertion;
  private final LongSupplier nanoTime;

  public PriorityInsertionEngine(ConstraintRegistry registry) {
    this(registry, System::nanoTime);
  }

  PriorityInsertionEngine(ConstraintRegistry registry, LongSupplier nanoTime) {
    this.registry = registry;
    this.insertion = new CheapestInsertion(registry);
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
    List<PlanOrder> placed = new ArrayList<>();
    for (PlanOrder o : problem.policy().rank(servable, ctx)) {
      if (nanoTime.getAsLong() > deadline) {
        partial = true;
        decisions.add(
            CheapestInsertion.deferred(o, TIMEOUT_RULE, "the engine reached its time budget before reaching this order", List.of()));
        continue;
      }
      Optional<OrderDecision> deferral = insertion.insertOrDefer(days, o, ctx);
      if (deferral.isPresent()) {
        decisions.add(deferral.get());
      } else {
        placed.add(o);
      }
    }

    for (PlanOrder o : placed) {
      decisions.add(insertion.served(o, days.values(), ctx));
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
    CheapestInsertion.Closest closest = null;
    for (FleetVehicle v : problem.fleet()) {
      List<ConstraintResult> results =
          registry.evaluate(new Candidate(VehicleDay.idle(v.asIdeal()).withNewTrip(o), ctx, Set.of()));
      if (ConstraintRegistry.allPass(results)) {
        return Optional.empty();
      }
      CheapestInsertion.Closest c = CheapestInsertion.Closest.of(results);
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
}
