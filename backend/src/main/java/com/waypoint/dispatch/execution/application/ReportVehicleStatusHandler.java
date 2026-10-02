package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The driver says what state the vehicle is in (R-EXE-06).
 *
 * <p>A report, not a change. Nothing in reference data moves: a dispatcher who
 * reads the report applies {@code vehicle:SetDayStatus}, so Reference stays a
 * module that consumes nothing (decision B18). A report of {@code fault} is
 * also announced, because that one cannot wait to be read.
 */
@Component
public class ReportVehicleStatusHandler implements CommandHandler {
  private static final Set<String> STATUSES = Set.of("available", "on_trip", "at_workshop", "fault");

  private final JdbcDeliveryRepository deliveries;
  private final DrivenVehicle vehicles;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ReportVehicleStatusHandler(
      JdbcDeliveryRepository deliveries,
      DrivenVehicle vehicles,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.deliveries = deliveries;
    this.vehicles = vehicles;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return ExecutionCommands.REPORT_VEHICLE_STATUS;
  }

  @Override
  public String action() {
    return ExecutionCommands.REPORT_VEHICLE_STATUS;
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
    CommandPayload payload = CommandPayload.of(command);
    String vehicleId = payload.requiredText("vehicleId");
    String status = payload.requiredText("status").toLowerCase(java.util.Locale.ROOT);
    if (!STATUSES.contains(status)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "status must be one of available, on_trip, at_workshop, fault");
    }
    String note = payload.text("note");
    if (note != null && note.length() > 500) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "note is at most 500 characters");
    }
    if (status.equals("fault") && note == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Say what is wrong with the vehicle in note");
    }
    Instant now = clock.now();
    DrivenVehicle.Today today = vehicles.require(vehicleId, now);

    deliveries.insertVehicleReport(
        UuidV7.generate(now, random), vehicleId, today.depotCode(), today.serviceDate(), "status", status,
        note, null, command.commandId(), ExecutionMessages.stamp(actor, command, now));
    if (status.equals("fault")) {
      events.publish(
          actor, new VehicleFaultReported(vehicleId, today.depotCode(), today.serviceDate(), note, now));
    }
    metrics.increment("waypoint.execution.vehicle_status", "status", status);
    return Map.of("vehicleId", vehicleId, "status", status, "serviceDate", today.serviceDate().toString());
  }
}
