package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher taking a served order off a draft, with a reason and their name
 * on it (R-PLN-19, rule 8). The order is deferred under {@code R-PLN-19}; the
 * next version of the draft is the result.
 */
@Component
public class DeferOrderHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final ConstraintRegistry registry;
  private final Metrics metrics;
  private final Clock clock;

  public DeferOrderHandler(PlanningDrafts drafts, ConstraintRegistry registry, Metrics metrics, Clock clock) {
    this.drafts = drafts;
    this.registry = registry;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.DEFER;
  }

  @Override
  public String action() {
    return PlanCommands.DEFER;
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
    String reason = PlanningDrafts.reason(payload);
    Instant now = clock.now();

    Opened opened = drafts.open(planId, expected);
    PlanningDrafts.requireSameDemand(opened);
    PlanningRun next =
        drafts.run(opened)
            .defer(
                drafts.newId(now),
                drafts.nextVersion(opened.row().depotCode(), opened.row().serviceDate()),
                orderId,
                reason,
                actor.userId(),
                registry,
                opened.built().problem().context());
    drafts.replace(opened, next, actor.userId(), now, command.commandId());
    metrics.increment("waypoint.plan.deferred_by_dispatcher");
    return PlanningDrafts.body(next);
  }
}
