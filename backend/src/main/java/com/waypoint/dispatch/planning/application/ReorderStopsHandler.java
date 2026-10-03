package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningRevisions.Place;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher fixing the order of one trip's stops. The timeline times the
 * stops in that order and the registry judges the result, so an order that
 * breaks a delivery window or a time budget is refused with the rule.
 */
@Component
public class ReorderStopsHandler extends DraftEditHandler {
  private final PlanningRevisions revisions;

  public ReorderStopsHandler(
      PlanningDrafts drafts, PlanningRevisions revisions, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
    this.revisions = revisions;
  }

  @Override
  public String kind() {
    return PlanCommands.REORDER_STOPS;
  }

  @Override
  public String action() {
    return PlanCommands.REORDER_STOPS;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.stops_reordered";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    UUID tripId = payload.uuid("tripId");
    Place place = revisions.locate(edit.opened().row().planId(), tripId, edit.current());
    return edit.current()
        .reorderStops(
            edit.nextPlanId(),
            edit.nextVersion(),
            place.vehicleId(),
            place.tripNumber(),
            uuids(edit.command(), "orderIds"),
            PlanningDrafts.reason(payload),
            registry,
            edit.opened().built().problem().context());
  }
}
