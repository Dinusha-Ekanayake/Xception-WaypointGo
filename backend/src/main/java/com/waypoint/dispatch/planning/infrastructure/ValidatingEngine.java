package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanVerification;
import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Re-checks every engine result against the registry before it leaves the
 * module (PLN-12). An engine is an optimisation; this is the guarantee, so a
 * smarter engine can never hand the dispatcher an infeasible plan.
 */
public final class ValidatingEngine implements AllocationEngine {
  private final AllocationEngine delegate;
  private final ConstraintRegistry registry;
  private final Consumer<List<Violation>> onRejected;

  public ValidatingEngine(AllocationEngine delegate, ConstraintRegistry registry, Consumer<List<Violation>> onRejected) {
    this.delegate = delegate;
    this.registry = registry;
    this.onRejected = onRejected;
  }

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public AllocationResult allocate(Problem problem) {
    AllocationResult result = delegate.allocate(problem);
    Set<java.util.UUID> demand = problem.orders().stream().map(PlanOrder::orderId).collect(Collectors.toSet());
    List<Violation> violations = PlanVerification.verify(result, demand, problem.context(), registry);
    if (!violations.isEmpty()) {
      onRejected.accept(violations);
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "engine " + delegate.name() + " returned an infeasible plan; it was rejected before leaving Planning",
          violations.stream().map(Violation::ruleId).distinct().toList());
    }
    // The rules plan offered beside it is a plan the dispatcher can switch to, so it is held to the same check;
    // a failing one is dropped rather than offered.
    if (result.alternative().isPresent()
        && !PlanVerification.verify(result.alternative().get(), demand, problem.context(), registry).isEmpty()) {
      onRejected.accept(List.of(new Violation("PLN-12", "alternative", "the rules plan failed its re-check")));
      return new AllocationResult(result.days(), result.decisions(), result.partial(), result.engine(),
          result.improvement(), result.cost(), java.util.Optional.empty());
    }
    return result;
  }
}
