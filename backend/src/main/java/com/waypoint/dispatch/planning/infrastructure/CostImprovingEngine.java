package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.CostReplan;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The plan the rules made, then the cost stage (planning v2): the same orders
 * on fewer vehicles and less fuel, only on a day worth searching (R-PLN-38,
 * R-PLN-39). When the stage changes the plan, the rules plan travels with it as
 * the alternative the dispatcher can compare and choose.
 *
 * <p>{@link ValidatingEngine} wraps this, so both plans are re-checked (PLN-12).
 */
public final class CostImprovingEngine implements AllocationEngine {
  private final AllocationEngine rules;
  private final CostReplan stage;
  private final LongSupplier nanoTime;

  public CostImprovingEngine(AllocationEngine rules, ConstraintRegistry registry) {
    this(rules, new CostReplan(registry), System::nanoTime);
  }

  CostImprovingEngine(AllocationEngine rules, CostReplan stage, LongSupplier nanoTime) {
    this.rules = rules;
    this.stage = stage;
    this.nanoTime = nanoTime;
  }

  @Override
  public String name() {
    return rules.name() + "+" + CostReplan.NAME;
  }

  @Override
  public AllocationResult allocate(Problem problem) {
    AllocationResult first = rules.allocate(problem);
    if (first.partial()) {
      return first;
    }
    // Its own budget from when the rules plan is ready: a day whose reefer search used the whole engine
    // budget still gets its cost stage, and the worst case stays bounded (R-PLN-39).
    long deadline = nanoTime.getAsLong() + CostReplan.budgetMillis(problem.rules()) * 1_000_000L;
    CostReplan.Result result = stage.improve(problem, first, nanoTime, deadline, name());
    AllocationResult out = result.allocation();
    return new AllocationResult(out.days(), out.decisions(), out.partial(), name(), first.improvement(),
        Optional.of(result.summary()), out.alternative());
  }
}
