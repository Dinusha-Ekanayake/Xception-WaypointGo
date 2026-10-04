package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Weekly fuel, Monday to Sunday (R-PLN-23). Fuel is physical, so the return leg
 * counts even though trip <em>time</em> excludes it (R-PLN-24, decision D-K):
 * out to the district, between the stops, and back.
 */
public final class FuelLedger {
  private FuelLedger() {}

  public static BigDecimal tripKm(Trip trip, DistrictTravel d) {
    int n = trip.orders().size();
    if (n == 0) {
      return BigDecimal.ZERO;
    }
    return d.outboundKm().multiply(BigDecimal.valueOf(2)).add(d.interStopKm().multiply(BigDecimal.valueOf(n - 1L)));
  }

  public static BigDecimal tripLitres(Trip trip, DistrictTravel d, FleetVehicle vehicle) {
    return tripKm(trip, d).divide(vehicle.kmPerL(), 3, RoundingMode.HALF_UP);
  }

  public static BigDecimal dayLitres(VehicleDay day, Map<String, DistrictTravel> travel) {
    return day.trips().stream()
        .map(t -> tripLitres(t, TripTimeline.travelFor(travel, t.district()), day.vehicle()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Quota minus other published plans this week minus this day; negative is an overrun. */
  public static BigDecimal remainingLitres(VehicleDay day, Map<String, DistrictTravel> travel) {
    FleetVehicle v = day.vehicle();
    return v.weeklyFuelQuotaL().subtract(v.fuelUsedThisWeekL()).subtract(dayLitres(day, travel));
  }
}
