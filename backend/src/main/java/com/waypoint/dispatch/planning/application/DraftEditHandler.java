package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A dispatcher's edit of an open draft. Each one finds the draft at the version
 * the caller saw, checks the demand has not changed, asks the domain for the
 * next version and stores it in place of this one, so two dispatchers editing
 * one draft collide on its row version (PLN-06). Subclasses supply only the
 * decision.
 */
abstract class DraftEditHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final Metrics metrics;
  private final Clock clock;
  protected final ConstraintRegistry registry;

  DraftEditHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    this.drafts = drafts;
    this.registry = registry;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** What the edit decided: the next version, from the draft it was handed. */
  protected record Edit(
      PlanningRun current, Opened opened, UUID nextPlanId, int nextVersion, Actor actor, Command command) {
    PlanOrder order(UUID orderId) {
      PlanOrder order = opened.built().orders().get(orderId);
      if (order == null) {
        throw new DomainException(
            ErrorCode.NOT_FOUND, "order " + orderId + " is not in plan " + opened.row().planId());
      }
      return order;
    }
  }

  protected abstract PlanningRun edit(Edit edit, CommandPayload payload);

  /** The counter this edit increments when it succeeds. */
  protected abstract String metric();

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
    Instant now = clock.now();

    Opened opened = drafts.open(planId, expected);
    PlanningDrafts.requireSameDemand(opened);
    Edit edit =
        new Edit(
            drafts.run(opened),
            opened,
            drafts.newId(now),
            drafts.nextVersion(opened.row().depotCode(), opened.row().serviceDate()),
            actor,
            command);
    PlanningRun next;
    try {
      next = edit(edit, payload);
    } catch (DomainException e) {
      metrics.increment(metric() + "_refused");
      throw e;
    }
    drafts.replace(opened, next, actor.userId(), now, command.commandId());
    metrics.increment(metric());
    return PlanningDrafts.body(next);
  }

  /** A list of ids from the payload, in the order given. */
  protected static List<UUID> uuids(Command command, String field) {
    JsonNode node = command.payload() == null ? null : command.payload().get(field);
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be a non-empty list of ids");
    }
    List<UUID> out = new ArrayList<>();
    for (JsonNode item : node) {
      try {
        out.add(UUID.fromString(item.asText()));
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " holds a value that is not an id");
      }
    }
    return out;
  }
}
