package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.ScarceFleetReplan;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * A first engine followed by {@link ScarceFleetReplan} (issue #92). The second
 * pass only replaces the first plan when it is strictly better by rank, so this
 * engine is never worse than the one it wraps. A partial first plan is returned
 * as it is: it already ran out of time (PLN-11).
 *
 * <p>{@link ValidatingEngine} wraps this, never the other way round, so the
 * improved plan is re-checked against the registry like any other (PLN-12).
 */
public final class ImprovingEngine implements AllocationEngine {
  private final AllocationEngine first;
  private final ScarceFleetReplan replan;
  private final LongSupplier nanoTime;

  public ImprovingEngine(AllocationEngine first, ConstraintRegistry registry) {
    this(first, new ScarceFleetReplan(registry), System::nanoTime);
  }

  ImprovingEngine(AllocationEngine first, ScarceFleetReplan replan, LongSupplier nanoTime) {
    this.first = first;
    this.replan = replan;
    this.nanoTime = nanoTime;
  }

  @Override
  public String name() {
    return first.name() + "+" + ScarceFleetReplan.NAME;
  }

  @Override
  public AllocationResult allocate(Problem problem) {
    long deadline = nanoTime.getAsLong() + problem.rules().engineBudgetMillis() * 1_000_000L;
    AllocationResult greedy = first.allocate(problem);
    if (greedy.partial()) {
      return greedy;
    }
    ScarceFleetReplan.Result result = replan.improve(problem, greedy, nanoTime, deadline, name());
    AllocationResult out = result.allocation();
    // The engine that ran is this one, whether or not it changed the first plan.
    return new AllocationResult(out.days(), out.decisions(), out.partial(), name(), Optional.of(result.summary()));
  }
}
