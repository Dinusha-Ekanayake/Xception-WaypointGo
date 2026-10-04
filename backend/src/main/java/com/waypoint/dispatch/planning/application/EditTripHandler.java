package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Place;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher setting what one trip carries and in what order, in one change:
 * orders added from the deferred list or another trip, orders taken off,
 * stops reordered, or the trip removed with an empty list (R-PLN-42). The
 * vehicle's whole day is judged, and a refusal names every failing rule.
 */
@Component
public class EditTripHandler extends DraftEditHandler {
  private final PlanningRevisions revisions;

  public EditTripHandler(
      PlanningDrafts drafts, PlanningRevisions revisions, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    super(drafts, registry, metrics, clock);
    this.revisions = revisions;
  }

  @Override
  public String kind() {
    return PlanCommands.EDIT_TRIP;
  }

  @Override
  public String action() {
    return PlanCommands.EDIT_TRIP;
  }

  @Override
  protected String metric() {
    return "waypoint.plan.trip_edited";
  }

  @Override
  protected PlanningRun edit(Edit edit, CommandPayload payload) {
    UUID tripId = payload.uuid("tripId");
    Place place = revisions.locate(edit.opened().row().planId(), tripId, edit.current());
    return edit.current()
        .editTrip(
            edit.nextPlanId(),
            edit.nextVersion(),
            place.vehicleId(),
            place.tripNumber(),
            orderIds(edit).stream().map(edit::order).toList(),
            PlanningDrafts.reason(payload),
            edit.actor().userId(),
            registry,
            edit.opened().built().problem().context());
  }

  /** The trip's orders in order; an empty list is allowed and removes the trip. */
  private static List<UUID> orderIds(Edit edit) {
    JsonNode node = edit.command().payload() == null ? null : edit.command().payload().get("orderIds");
    if (node == null || !node.isArray()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "orderIds must be a list of ids, empty to remove the trip");
    }
    List<UUID> out = new ArrayList<>();
    for (JsonNode item : node) {
      try {
        out.add(UUID.fromString(item.asText()));
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "orderIds holds a value that is not an id");
      }
    }
    return out;
  }
}
