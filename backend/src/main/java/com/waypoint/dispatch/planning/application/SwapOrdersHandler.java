package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher trading a served order for a deferred one. The deferred order
 * takes the trip the served one leaves, and the vehicle's whole day is checked
 * against the registry, so the swap happens whole or not at all (R-PLN-19).
 */
@Component
public class SwapOrdersHandler extends DraftEditHandler {

  public SwapOrdersHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
  }

  @Override
  public String kind() {
    return PlanCommands.SWAP;
  }

  @Override
  public String action() {
    return PlanCommands.SWAP;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.swapped";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    UUID out = payload.uuid("outOrderId");
    UUID in = payload.uuid("inOrderId");
    return edit.current()
        .swap(
            edit.nextPlanId(),
            edit.nextVersion(),
            edit.order(out),
            edit.order(in),
            PlanningDrafts.reason(payload),
            edit.actor().userId(),
            registry,
            edit.opened().built().problem().context());
  }
}
