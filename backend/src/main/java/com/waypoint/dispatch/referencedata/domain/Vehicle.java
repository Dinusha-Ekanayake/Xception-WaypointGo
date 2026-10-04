package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.DepotCode;
import java.math.BigDecimal;

/**
 * A delivery vehicle.
 *
 * <p>Capacities are {@code BigDecimal} deliberately (convention B3): a load that
 * "fits" by a rounding error is a truck that physically does not close.
 */
public record Vehicle(
    String id,
    VehicleType type,
    TemperatureCapability temperature,
    BigDecimal weightCapKg,
    BigDecimal volumeCapM3,
    String fuelType,
    BigDecimal kmPerL,
    BigDecimal weeklyFuelQuotaL,
    DepotCode depot) {

  /** R-PLN-02. */
  public boolean canCarry(TemperatureRequirement requirement) {
    return temperature.canCarry(requirement);
  }

  /** R-PLN-03. */
  public boolean canReach(Outlet outlet) {
    return !outlet.requiresVan() || type == VehicleType.VAN;
  }
}
