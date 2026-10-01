package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * A report is about a vehicle the actor is driving today. "Today" is the
 * operating day by the server's clock, so a driver assigned yesterday cannot
 * report for it today (EXE-13).
 */
@Component
class DrivenVehicle {
  private final JdbcDeliveryRepository deliveries;
  private final ReferenceQuery reference;

  DrivenVehicle(JdbcDeliveryRepository deliveries, ReferenceQuery reference) {
    this.deliveries = deliveries;
    this.reference = reference;
  }

  record Today(String depotCode, LocalDate serviceDate) {}

  /** One refusal for an unknown vehicle and for one the actor does not drive, so neither is disclosed. */
  Today require(String vehicleId, Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    var vehicle = reference.vehicle(vehicleId, null);
    if (vehicle.isEmpty() || !deliveries.mayRecordFor(vehicleId, today)) {
      throw new DomainException(ErrorCode.FORBIDDEN, "You are not assigned to this vehicle today");
    }
    return new Today(vehicle.get().depotCode(), today);
  }
}
