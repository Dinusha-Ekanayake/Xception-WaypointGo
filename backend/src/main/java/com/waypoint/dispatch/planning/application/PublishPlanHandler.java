package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A dispatcher publishing a draft, through the gate in {@link PlanPublication}. */
@Component
public class PublishPlanHandler implements CommandHandler {
  private final PlanPublication publication;
  private final Clock clock;

  PublishPlanHandler(PlanPublication publication, Clock clock) {
    this.publication = publication;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.PUBLISH;
  }

  @Override
  public String action() {
    return PlanCommands.PUBLISH;
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
    return publication.publish(
        actor, CommandPayload.of(command).uuid("planId"), PlanningDrafts.expectedVersion(command), clock.now());
  }
}
