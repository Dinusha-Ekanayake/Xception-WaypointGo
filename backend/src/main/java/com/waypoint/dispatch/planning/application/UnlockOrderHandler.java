package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.stereotype.Component;

/** A dispatcher letting a held order go, so a regenerate may move it again. */
@Component
public class UnlockOrderHandler extends DraftEditHandler {

  public UnlockOrderHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
  }

  @Override
  public String kind() {
    return PlanCommands.UNLOCK;
  }

  @Override
  public String action() {
    return PlanCommands.UNLOCK;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.unlocked";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    return edit.current()
        .lock(
            edit.nextPlanId(),
            edit.nextVersion(),
            payload.uuid("orderId"),
            false,
            edit.actor().userId(),
            registry,
            edit.opened().built().problem().context());
  }
}
