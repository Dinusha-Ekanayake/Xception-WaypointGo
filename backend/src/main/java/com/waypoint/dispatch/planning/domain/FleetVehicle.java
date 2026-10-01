package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;

/**
 * A vehicle on the planning day.
 *
 * @param available false for {@code in_workshop} or unavailable (R-FLT-03)
 * @param fuelUsedThisWeekL litres already committed by other published plans in
 *     the same ISO week, return legs included (R-PLN-24, decision D-K)
 */
public record FleetVehicle(
    String vehicleId,
    String depotCode,
    boolean van,
    boolean reefer,
    BigDecimal weightCapKg,
    BigDecimal volumeCapM3,
    BigDecimal kmPerL,
    BigDecimal weeklyFuelQuotaL,
    boolean available,
    BigDecimal fuelUsedThisWeekL) {

  /** The same vehicle as if it were free and unused, for the unservable screen (R-PLN-22). */
  public FleetVehicle asIdeal() {
    return new FleetVehicle(
        vehicleId,
        depotCode,
        van,
        reefer,
        weightCapKg,
        volumeCapM3,
        kmPerL,
        weeklyFuelQuotaL,
        true,
        BigDecimal.ZERO);
  }
}
