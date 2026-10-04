package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.platform.observability.Metrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one registry and the engine behind the port: priority insertion, then the
 * scarce-fleet re-plan (issue #92), then the cost stage (planning v2). Whatever engine sits here,
 * {@link ValidatingEngine} wraps it, so a replacement can never hand a draft an
 * infeasible plan (PLN-12).
 */
@Configuration
public class PlanningEngineConfiguration {

  @Bean
  public ConstraintRegistry planningConstraints() {
    return ConstraintRegistry.standard();
  }

  @Bean
  public AllocationEngine allocationEngine(ConstraintRegistry planningConstraints, Metrics metrics) {
    return new ValidatingEngine(
        new CostImprovingEngine(
            new ImprovingEngine(new PriorityInsertionEngine(planningConstraints), planningConstraints),
            planningConstraints),
        planningConstraints,
        violations -> metrics.increment("waypoint.plan.engine_rejected"));
  }
}
