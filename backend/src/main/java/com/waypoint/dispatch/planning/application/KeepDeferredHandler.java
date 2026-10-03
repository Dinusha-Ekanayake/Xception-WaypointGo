package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.stereotype.Component;

/**
 * A dispatcher deciding that deferred orders stay deferred. Nothing moves; the
 * decision is recorded with their name and reason, which is what lets a plan
 * with deferred orders be published knowing someone looked (rule 8).
 */
@Component
public class KeepDeferredHandler extends DraftEditHandler {

  public KeepDeferredHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
  }

  @Override
  public String kind() {
    return PlanCommands.KEEP_DEFERRED;
  }

  @Override
  public String action() {
    return PlanCommands.KEEP_DEFERRED;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.kept_deferred";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    return edit.current()
        .keepDeferred(
            edit.nextPlanId(),
            edit.nextVersion(),
            uuids(edit.command(), "orderIds"),
            PlanningDrafts.reason(payload),
            edit.actor().userId(),
            registry,
            edit.opened().built().problem().context());
  }
}
