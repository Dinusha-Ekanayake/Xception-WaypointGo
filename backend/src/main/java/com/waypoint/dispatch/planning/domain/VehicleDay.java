package com.waypoint.dispatch.planning.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything one vehicle does on the planning day. Trip numbers are positions
 * in this list, starting at 1: Fresh trips run pre-dawn, so they come first, and
 * otherwise the order trips were opened is kept.
 *
 * <p>Trip count, time budget, delivery windows and fuel are all properties of
 * the vehicle's whole day, not of one trip, which is why constraints evaluate a
 * {@code VehicleDay}.
 */
public record VehicleDay(FleetVehicle vehicle, List<Trip> trips) {

  public VehicleDay {
    List<Trip> sorted = new ArrayList<>(trips);
    sorted.removeIf(t -> t.orders().isEmpty());
    sorted.sort(Comparator.comparing((Trip t) -> !t.fresh()));
    trips = List.copyOf(sorted);
  }

  public static VehicleDay idle(FleetVehicle vehicle) {
    return new VehicleDay(vehicle, List.of());
  }

  public String vehicleId() {
    return vehicle.vehicleId();
  }

  public VehicleDay withNewTrip(PlanOrder order) {
    List<Trip> next = new ArrayList<>(trips);
    next.add(Trip.of(order));
    return new VehicleDay(vehicle, next);
  }

  /** @param tripNumber 1-based */
  public VehicleDay withJoined(int tripNumber, PlanOrder order) {
    List<Trip> next = new ArrayList<>(trips);
    next.set(tripNumber - 1, next.get(tripNumber - 1).with(order));
    return new VehicleDay(vehicle, next);
  }

  public VehicleDay without(UUID orderId) {
    return new VehicleDay(vehicle, trips.stream().map(t -> t.without(orderId)).toList());
  }

  public Optional<Integer> tripNumberOf(UUID orderId) {
    for (int i = 0; i < trips.size(); i++) {
      if (trips.get(i).contains(orderId)) {
        return Optional.of(i + 1);
      }
    }
    return Optional.empty();
  }

  public boolean carries(UUID orderId) {
    return tripNumberOf(orderId).isPresent();
  }

  public Trip trip(int tripNumber) {
    return trips.get(tripNumber - 1);
  }
}
