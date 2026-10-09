package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a depot's fleet can carry in a week, to set a forecast against.
 *
 * <p>Every vehicle of the depot's reference fleet, with the effective trip limit for each day,
 * on each operating day of the week. It is the fleet as registered, not one
 * day's availability: a vehicle in the workshop next week is unknown today, so
 * the figure is an upper bound (A-40). Refrigerated capacity counts reefers
 * only, because chilled goods ride only in them (D-J).
 */
public final class FleetCapacity {
  private FleetCapacity() {}

  public record Vehicle(BigDecimal volumeM3, boolean refrigerated) {}

  public record Week(int vehicles, int refrigeratedVehicles, BigDecimal fleetM3, BigDecimal refrigeratedM3) {}

  /** One operating day: the vehicles available that day and the trips each may run. */
  public record Day(List<Vehicle> available, int trips) {}

  /**
   * What a depot can carry in a week with each day's own fleet: a vehicle booked
   * into the workshop or marked unavailable on a day carries nothing that day
   * (roadmap T2-01). The counts are the fewest available on any operating day,
   * because a week that needs every reefer daily is not covered when three are
   * in the workshop on Wednesday. No operating days: nothing, and no vehicles.
   */
  public static Week weeklyByDay(List<Day> operatingDays) {
    BigDecimal fleetM3 = BigDecimal.ZERO;
    BigDecimal reeferM3 = BigDecimal.ZERO;
    int fewest = Integer.MAX_VALUE;
    int fewestReefers = Integer.MAX_VALUE;
    for (Day day : operatingDays) {
      BigDecimal trips = BigDecimal.valueOf(day.trips());
      int reefers = 0;
      for (Vehicle v : day.available()) {
        fleetM3 = fleetM3.add(v.volumeM3().multiply(trips));
        if (v.refrigerated()) {
          reeferM3 = reeferM3.add(v.volumeM3().multiply(trips));
          reefers++;
        }
      }
      fewest = Math.min(fewest, day.available().size());
      fewestReefers = Math.min(fewestReefers, reefers);
    }
    if (operatingDays.isEmpty()) {
      fewest = 0;
      fewestReefers = 0;
    }
    return new Week(fewest, fewestReefers, fleetM3.setScale(2, RoundingMode.HALF_UP), reeferM3.setScale(2, RoundingMode.HALF_UP));
  }

  public static Week weekly(List<Vehicle> fleet, List<Integer> tripsByOperatingDay) {
    BigDecimal perDay = BigDecimal.ZERO;
    BigDecimal reeferPerDay = BigDecimal.ZERO;
    int reefers = 0;
    for (Vehicle v : fleet) {
      BigDecimal trips = v.volumeM3();
      perDay = perDay.add(trips);
      if (v.refrigerated()) {
        reeferPerDay = reeferPerDay.add(trips);
        reefers++;
      }
    }
    BigDecimal trips = BigDecimal.valueOf(tripsByOperatingDay.stream().mapToInt(Integer::intValue).sum());
    return new Week(
        fleet.size(),
        reefers,
        perDay.multiply(trips).setScale(2, RoundingMode.HALF_UP),
        reeferPerDay.multiply(trips).setScale(2, RoundingMode.HALF_UP));
  }
}
