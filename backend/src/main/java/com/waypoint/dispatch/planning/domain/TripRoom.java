package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether one more order joins a trip already going to a district, or would
 * need a trip of its own (issue #199, R-ORD-14).
 *
 * <p>The booked orders of one brand and district are packed onto the day's
 * available vehicles the way a trip is built: each joins an open trip when the
 * load rules pass, else opens one on the largest vehicle not yet out. The extra
 * order rides along when some trip still passes with it on. Only the load
 * rules of the registry are asked (temperature, van access, weight and volume
 * with the validator's epsilon), by their own instances, so capacity keeps one
 * definition (rule 5). Time budgets, windows and fuel need a route, which a
 * store choosing a day weeks ahead does not have; the plan still decides.
 */
public final class TripRoom {
  /** The registry's constraints that judge a load without a route. */
  public static final Set<String> LOAD_RULES =
      Set.of("SingleTemperaturePerTrip", "Temperature", "VanOnlyAccess", "WeightCapacity", "VolumeCapacity");

  private TripRoom() {}

  /**
   * @param fleet the depot's vehicles, with whether each is available that day
   * @param booked other outlets' measured orders of the same brand and district that day
   * @param extra the store's order, measured or estimated
   */
  public static boolean joins(
      List<FleetVehicle> fleet, List<PlanOrder> booked, PlanOrder extra, PlanContext context,
      ConstraintRegistry registry) {
    if (booked.isEmpty()) {
      return false;
    }
    ConstraintRegistry load = new ConstraintRegistry(
        registry.constraints().stream().filter(c -> LOAD_RULES.contains(c.name())).toList());
    List<FleetVehicle> vehicles = fleet.stream()
        .filter(FleetVehicle::available)
        .sorted(Comparator.comparing(FleetVehicle::volumeCapM3).reversed()
            .thenComparing(Comparator.comparing(FleetVehicle::weightCapKg).reversed())
            .thenComparing(FleetVehicle::vehicleId))
        .toList();
    List<PlanOrder> largestFirst = booked.stream()
        .sorted(Comparator.comparing(PlanOrder::volumeM3).reversed().thenComparing(PlanOrder::orderId))
        .toList();

    List<VehicleDay> trips = new ArrayList<>();
    Set<String> out = new HashSet<>();
    for (PlanOrder o : largestFirst) {
      boolean placed = false;
      for (int i = 0; i < trips.size() && !placed; i++) {
        VehicleDay joined = trips.get(i).withJoined(1, o);
        if (passes(joined, context, load)) {
          trips.set(i, joined);
          placed = true;
        }
      }
      for (int i = 0; i < vehicles.size() && !placed; i++) {
        FleetVehicle v = vehicles.get(i);
        VehicleDay alone = VehicleDay.idle(v).withNewTrip(o);
        if (!out.contains(v.vehicleId()) && passes(alone, context, load)) {
          trips.add(alone);
          out.add(v.vehicleId());
          placed = true;
        }
      }
      // An order no vehicle can take is the plan's to call unservable; it adds no trip here.
    }
    return trips.stream().anyMatch(t -> passes(t.withJoined(1, extra), context, load));
  }

  private static boolean passes(VehicleDay day, PlanContext context, ConstraintRegistry load) {
    return ConstraintRegistry.allPass(load.evaluate(new Candidate(day, context, Set.of())));
  }
}
