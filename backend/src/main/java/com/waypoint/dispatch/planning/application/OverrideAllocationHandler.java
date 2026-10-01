package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher moving an order onto a vehicle and trip of a draft. The same
 * registry the engine used decides; a violation is returned with its rules,
 * never accepted (MODULES §4). The result is the next version of the draft.
 */
@Component
public class OverrideAllocationHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final ConstraintRegistry registry;
  private final Metrics metrics;
  private final Clock clock;

  public OverrideAllocationHandler(
      PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    this.drafts = drafts;
    this.registry = registry;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.OVERRIDE;
  }

  @Override
  public String action() {
    return PlanCommands.OVERRIDE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.PLANNING;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("planId");
    return id == null ? null : "wpt:plan:plan:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = PlanningDrafts.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID planId = payload.uuid("planId");
    UUID orderId = payload.uuid("orderId");
    String vehicleId = payload.requiredText("vehicleId");
    int tripNumber = tripNumber(command);
    String reason = PlanningDrafts.reason(payload);
    Instant now = clock.now();

    Opened opened = drafts.open(planId, expected);
    PlanningDrafts.requireSameDemand(opened);
    PlanOrder order = opened.built().orders().get(orderId);
    if (order == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "order " + orderId + " is not in plan " + planId);
    }
    FleetVehicle vehicle = opened.built().fleet().get(vehicleId);
    if (vehicle == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, vehicleId + " is not a vehicle of depot " + opened.row().depotCode());
    }

    PlanningRun next;
    try {
      next =
          drafts.run(opened)
              .override(
                  drafts.newId(now),
                  drafts.nextVersion(opened.row().depotCode(), opened.row().serviceDate()),
                  order,
                  vehicle,
                  tripNumber,
                  reason,
                  registry,
                  opened.built().problem().context());
    } catch (DomainException e) {
      metrics.increment("waypoint.plan.override_refused");
      throw e;
    }
    drafts.replace(opened, next, actor.userId(), now, command.commandId());
    metrics.increment("waypoint.plan.overridden");
    return PlanningDrafts.body(next);
  }

  private static int tripNumber(Command command) {
    JsonNode node = command.payload() == null ? null : command.payload().get("tripNumber");
    if (node == null || !node.canConvertToInt()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "tripNumber is required and must be an integer");
    }
    return node.asInt();
  }
}
