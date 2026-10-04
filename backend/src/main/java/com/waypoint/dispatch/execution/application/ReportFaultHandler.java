package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.RoadDisruptionReported;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.Stamp;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Something is wrong with the vehicle or the road (R-EXE-07, R-EXE-09).
 *
 * <p>The report is recorded and announced; what to do about it is the
 * dispatcher's decision. For a breakdown that is {@code vehicle:SetDayStatus}
 * and a replan, after which the stops that left the trip are skipped here
 * (EXE-08). The stops themselves are not touched by a report.
 */
@Component
public class ReportFaultHandler implements CommandHandler {
  private static final int DESCRIPTION_LIMIT = 500;

  private final JdbcDeliveryRepository deliveries;
  private final DrivenVehicle vehicles;
  private final ReferenceQuery reference;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ReportFaultHandler(
      JdbcDeliveryRepository deliveries,
      DrivenVehicle vehicles,
      ReferenceQuery reference,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.deliveries = deliveries;
    this.vehicles = vehicles;
    this.reference = reference;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return ExecutionCommands.REPORT_FAULT;
  }

  @Override
  public String action() {
    return ExecutionCommands.REPORT_FAULT;
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
    String kind = payload.requiredText("kind").toLowerCase(java.util.Locale.ROOT);
    if (!kind.equals("vehicle") && !kind.equals("road")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "kind must be vehicle or road");
    }
    String description = ExecutionMessages.boundedText(command, "description", DESCRIPTION_LIMIT);
    UUID deliveryId = payload.optionalUuid("deliveryId");
    Instant now = clock.now();

    DrivenVehicle.Today today = vehicles.require(vehicleId, now);
    Optional<DeliveryRecord> stop =
        Optional.ofNullable(deliveryId).map(id -> ExecutionMessages.loadForWrite(deliveries, id));
    if (stop.filter(s -> !s.vehicleId().equals(vehicleId)).isPresent()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "That stop is not on vehicle " + vehicleId);
    }

    UUID reportId = UuidV7.generate(now, random);
    Stamp stamp = ExecutionMessages.stamp(actor, command, now);
    if (kind.equals("vehicle")) {
      deliveries.insertVehicleReport(
          reportId, vehicleId, today.depotCode(), today.serviceDate(), "fault", null, description,
          deliveryId, command.commandId(), stamp);
      events.publish(
          actor,
          new VehicleFaultReported(vehicleId, today.depotCode(), today.serviceDate(), description, now));
    } else {
      // Where it happened, when the report is made at a stop.
      Optional<String> district =
          stop.flatMap(s -> reference.outlet(s.outletId(), null)).map(o -> o.districtName());
      deliveries.insertRoadReport(
          reportId, vehicleId, today.depotCode(), today.serviceDate(), district.orElse(null), description,
          deliveryId, command.commandId(), stamp);
      events.publish(
          actor, new RoadDisruptionReported(vehicleId, today.depotCode(), district, description, now));
    }
    metrics.increment("waypoint.execution.fault_reported", "kind", kind);
    return Map.of("reportId", reportId.toString(), "kind", kind);
  }
}
