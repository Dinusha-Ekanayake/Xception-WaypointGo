package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.shared.domain.Actor;
import org.springframework.stereotype.Component;

@Component
public class CreateDepotHandler implements CommandHandler {
  private final ManagedReferencePublisher publisher;
  public CreateDepotHandler(ManagedReferencePublisher publisher) { this.publisher = publisher; }
  public String kind() { return "reference:CreateDepot"; }
  public String action() { return kind(); }
  public ModuleRole moduleRole() { return ModuleRole.REF; }
  public String resource(Command command) { return "wpt:ref:depot:*"; }
  public Object handle(Actor actor, Command command) {
    return publisher.create("depot", actor, command.payload());
  }
}
