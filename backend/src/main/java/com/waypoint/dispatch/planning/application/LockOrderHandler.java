package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.stereotype.Component;

/**
 * A dispatcher holding an order on its trip, so a regenerate keeps it there
 * (see {@link UnlockOrderHandler} for letting it go).
 */
@Component
public class LockOrderHandler extends DraftEditHandler {

  public LockOrderHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
  }

  @Override
  public String kind() {
    return PlanCommands.LOCK;
  }

  @Override
  public String action() {
    return PlanCommands.LOCK;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.locked";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    return edit.current()
        .lock(
            edit.nextPlanId(),
            edit.nextVersion(),
            payload.uuid("orderId"),
            true,
            edit.actor().userId(),
            registry,
            edit.opened().built().problem().context());
  }
}
