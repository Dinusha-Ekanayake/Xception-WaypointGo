package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import org.springframework.stereotype.Component;

@Component
public class UpdateVehicleHandler implements CommandHandler {
  private final ManagedReferencePublisher publisher;
  public UpdateVehicleHandler(ManagedReferencePublisher publisher) { this.publisher = publisher; }
  public String kind() { return "reference:UpdateVehicle"; }
  public String action() { return kind(); }
  public ModuleRole moduleRole() { return ModuleRole.REF; }
  public String resource(Command command) { return "wpt:ref:vehicle:" + CommandPayload.of(command).requiredText("vehicleId"); }
  public Object handle(Actor actor, Command command) { return publisher.update("vehicle", actor, command.payload(), command.expectedVersion()); }
}
