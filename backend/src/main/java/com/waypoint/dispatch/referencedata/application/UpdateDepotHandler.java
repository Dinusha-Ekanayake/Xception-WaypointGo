package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import org.springframework.stereotype.Component;

@Component
public class UpdateDepotHandler implements CommandHandler {
  private final ManagedReferencePublisher publisher;
  public UpdateDepotHandler(ManagedReferencePublisher publisher) { this.publisher = publisher; }
  public String kind() { return "reference:UpdateDepot"; }
  public String action() { return kind(); }
  public ModuleRole moduleRole() { return ModuleRole.REF; }
  public String resource(Command command) { return "wpt:ref:depot:" + CommandPayload.of(command).requiredText("code"); }
  public Object handle(Actor actor, Command command) { return publisher.update("depot", actor, command.payload(), command.expectedVersion()); }
}
