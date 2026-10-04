package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleAtDepot;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * R-EXE-24: the driver has collected the vehicle and is at the depot, ready
 * for it to be loaded. Announced as {@code vehicle.at_depot}, which Loading
 * marks on the dock board so the loader takes that trip next, and which tells
 * the depot's loaders.
 *
 * <p>Only the driver assigned to the vehicle today may say so ({@link
 * DrivenVehicle}, R-EXE-20); anyone else is refused and audited. Saying it twice
 * is harmless: Loading keeps the first time.
 */
@Component
public class ArriveAtDepotHandler implements CommandHandler {
  private final DrivenVehicle vehicles;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public ArriveAtDepotHandler(DrivenVehicle vehicles, EventPublisher events, Metrics metrics, Clock clock) {
    this.vehicles = vehicles;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return ExecutionCommands.ARRIVE_AT_DEPOT;
  }

  @Override
  public String action() {
    return ExecutionCommands.ARRIVE_AT_DEPOT;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String resource(Command command) {
    return ExecutionMessages.vehicleResource(command);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    String vehicleId = CommandPayload.of(command).requiredText("vehicleId");
    Instant now = clock.now();
    DrivenVehicle.Today today = vehicles.require(vehicleId, now);
    events.publish(
        actor, new VehicleAtDepot(vehicleId, today.depotCode(), today.serviceDate(), actor.userId(), now));
    metrics.increment("waypoint.execution.vehicle_at_depot");
    return Map.of(
        "vehicleId", vehicleId, "depotCode", today.depotCode(), "serviceDate", today.serviceDate().toString(),
        "arrivedAt", now.toString());
  }
}
