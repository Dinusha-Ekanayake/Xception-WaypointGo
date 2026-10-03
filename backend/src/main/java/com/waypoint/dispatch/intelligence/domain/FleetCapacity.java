package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a depot's fleet can carry in a week, to set a forecast against.
 *
 * <p>Every vehicle of the depot's reference fleet, two trips a day (R-PLN-07),
 * on each operating day of the week. It is the fleet as registered, not one
 * day's availability: a vehicle in the workshop next week is unknown today, so
 * the figure is an upper bound (A-40). Refrigerated capacity counts reefers
 * only, because chilled goods ride only in them (D-J).
 */
public final class FleetCapacity {
  /** R-PLN-07: a vehicle runs at most two trips a day. */
  public static final int TRIPS_PER_DAY = 2;

  private FleetCapacity() {}

  public record Vehicle(BigDecimal volumeM3, boolean refrigerated) {}

  public record Week(int vehicles, int refrigeratedVehicles, BigDecimal fleetM3, BigDecimal refrigeratedM3) {}

  public static Week weekly(List<Vehicle> fleet, int operatingDays) {
    BigDecimal perDay = BigDecimal.ZERO;
    BigDecimal reeferPerDay = BigDecimal.ZERO;
    int reefers = 0;
    for (Vehicle v : fleet) {
      BigDecimal trips = v.volumeM3().multiply(BigDecimal.valueOf(TRIPS_PER_DAY));
      perDay = perDay.add(trips);
      if (v.refrigerated()) {
        reeferPerDay = reeferPerDay.add(trips);
        reefers++;
      }
    }
    BigDecimal days = BigDecimal.valueOf(Math.max(0, operatingDays));
    return new Week(
        fleet.size(),
        reefers,
        perDay.multiply(days).setScale(2, RoundingMode.HALF_UP),
        reeferPerDay.multiply(days).setScale(2, RoundingMode.HALF_UP));
  }
}
