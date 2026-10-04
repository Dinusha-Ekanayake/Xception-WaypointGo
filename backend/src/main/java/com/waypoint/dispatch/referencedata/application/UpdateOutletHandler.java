package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import org.springframework.stereotype.Component;

@Component
public class UpdateOutletHandler implements CommandHandler {
  private final ManagedReferencePublisher publisher;

  public UpdateOutletHandler(ManagedReferencePublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public String kind() {
    return "reference:UpdateOutlet";
  }

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.REF;
  }

  @Override
  public String resource(Command command) {
    return "wpt:ref:outlet:" + CommandPayload.of(command).requiredText("outletId");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    return publisher.update("outlet", actor, command.payload(), command.expectedVersion());
  }
}
