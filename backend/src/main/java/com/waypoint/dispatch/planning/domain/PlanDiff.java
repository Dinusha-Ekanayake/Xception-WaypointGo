package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.ChangeKind;
import com.waypoint.dispatch.planning.contract.PlanViews.ComparisonView;
import com.waypoint.dispatch.planning.contract.PlanViews.OrderChange;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanSideView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlaceView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What differs between two plans of one depot and day: how many orders each
 * serves, which orders moved, were added or dropped, and which trips of the
 * second a driver would see differently. Pure: two plans in, a comparison out.
 * Compare uses it to show two plans side by side, and a revision uses it to
 * tell only the drivers and stores a change touches.
 */
public final class PlanDiff {
  private PlanDiff() {}

  public static ComparisonView compare(String labelA, PlanView a, String labelB, PlanView b) {
    Map<UUID, Place> placeA = places(a);
    Map<UUID, Place> placeB = places(b);
    Map<UUID, String> outlets = new HashMap<>();
    for (PlanView plan : List.of(a, b)) {
      for (TripView trip : plan.trips()) {
        for (StopView stop : trip.stops()) {
          outlets.put(stop.orderId(), stop.outletId());
        }
      }
    }

    List<OrderChange> changes = new ArrayList<>();
    for (UUID orderId : new TreeSet<>(union(placeA.keySet(), placeB.keySet()))) {
      Place before = placeA.get(orderId);
      Place after = placeB.get(orderId);
      Optional<ChangeKind> kind = kind(before, after);
      if (kind.isEmpty()) {
        continue;
      }
      changes.add(new OrderChange(orderId, Optional.ofNullable(outlets.get(orderId)), kind.get(), view(before), view(after)));
    }

    return new ComparisonView(
        side(labelA, a),
        side(labelB, b),
        changes,
        changedTrips(a, b),
        removedTrips(a, b),
        affectedOutlets(a, b));
  }

  /**
   * Outlets with an order on a trip in {@code b} that is not where, or when,
   * {@code a} had it: placed there new, moved to another vehicle or trip, or
   * reached at another time. An order {@code b} drops is not counted: the
   * outlet hears of that as a deferral, with its reason.
   */
  public static List<String> affectedOutlets(PlanView a, PlanView b) {
    Map<UUID, String> before = stopKeys(a);
    Map<UUID, String> outlets = new HashMap<>();
    Set<String> out = new TreeSet<>();
    for (TripView trip : b.trips()) {
      for (StopView stop : trip.stops()) {
        outlets.put(stop.orderId(), stop.outletId());
        String key = trip.vehicleId() + "|" + trip.tripNumber() + "|" + stop.plannedArrival();
        if (!key.equals(before.get(stop.orderId()))) {
          out.add(stop.outletId());
        }
      }
    }
    return List.copyOf(out);
  }

  private static Map<UUID, String> stopKeys(PlanView plan) {
    Map<UUID, String> out = new HashMap<>();
    for (TripView trip : plan.trips()) {
      for (StopView stop : trip.stops()) {
        out.put(stop.orderId(), trip.vehicleId() + "|" + trip.tripNumber() + "|" + stop.plannedArrival());
      }
    }
    return out;
  }

  /** Trips of {@code b} a driver would see differently from {@code a}: new, or on another vehicle, time or stops. */
  public static List<UUID> changedTrips(PlanView a, PlanView b) {
    Map<UUID, String> before = tripKeys(a);
    return b.trips().stream()
        .filter(t -> !tripKey(t).equals(before.get(t.tripId())))
        .map(TripView::tripId)
        .toList();
  }

  /** Trips of {@code a} that {@code b} no longer has. */
  public static List<UUID> removedTrips(PlanView a, PlanView b) {
    Set<UUID> after = b.trips().stream().map(TripView::tripId).collect(Collectors.toSet());
    return a.trips().stream().map(TripView::tripId).filter(id -> !after.contains(id)).toList();
  }

  // ---- internals -------------------------------------------------------------

  private record Place(AllocationDecision decision, Optional<String> vehicleId, Optional<Integer> tripNumber) {}

  private static Map<UUID, Place> places(PlanView plan) {
    Map<UUID, TripView> trips = new HashMap<>();
    plan.trips().forEach(t -> trips.put(t.tripId(), t));
    Map<UUID, Place> out = new HashMap<>();
    for (AllocationView a : plan.allocations()) {
      Optional<TripView> trip = a.tripId().map(trips::get);
      out.put(a.orderId(), new Place(a.decision(), trip.map(TripView::vehicleId), trip.map(TripView::tripNumber)));
    }
    return out;
  }

  private static Optional<ChangeKind> kind(Place before, Place after) {
    boolean wasServed = before != null && before.decision() == AllocationDecision.SERVED;
    boolean isServed = after != null && after.decision() == AllocationDecision.SERVED;
    if (wasServed && isServed) {
      return before.vehicleId().equals(after.vehicleId()) && before.tripNumber().equals(after.tripNumber())
          ? Optional.empty()
          : Optional.of(ChangeKind.MOVED);
    }
    if (!wasServed && isServed) {
      return Optional.of(ChangeKind.ADDED);
    }
    if (wasServed) {
      return Optional.of(ChangeKind.DROPPED);
    }
    return Optional.empty();
  }

  private static PlaceView view(Place place) {
    return place == null
        ? new PlaceView(Optional.empty(), Optional.empty(), Optional.empty())
        : new PlaceView(Optional.of(place.decision()), place.vehicleId(), place.tripNumber());
  }

  private static PlanSideView side(String label, PlanView plan) {
    long served = plan.allocations().stream().filter(x -> x.decision() == AllocationDecision.SERVED).count();
    long deferred = plan.allocations().stream().filter(x -> x.decision() == AllocationDecision.DEFERRED).count();
    long unservable = plan.allocations().stream().filter(x -> x.decision() == AllocationDecision.UNSERVABLE).count();
    return new PlanSideView(
        label,
        plan.planId(),
        plan.planVersion(),
        (int) served,
        (int) deferred,
        (int) unservable,
        plan.trips().size(),
        (int) plan.trips().stream().map(TripView::vehicleId).distinct().count());
  }

  private static Map<UUID, String> tripKeys(PlanView plan) {
    Map<UUID, String> out = new HashMap<>();
    plan.trips().forEach(t -> out.put(t.tripId(), tripKey(t)));
    return out;
  }

  /** Everything a driver sees of a trip: the vehicle, its number, when it leaves and each stop with its time. */
  private static String tripKey(TripView t) {
    return t.vehicleId() + "|" + t.tripNumber() + "|" + t.plannedDeparture() + "|"
        + t.stops().stream()
            .sorted(Comparator.comparingInt(StopView::sequence))
            .map(s -> s.orderId() + "@" + s.plannedArrival())
            .collect(Collectors.joining(","));
  }

  private static <T> Set<T> union(Set<T> a, Set<T> b) {
    Set<T> out = new java.util.HashSet<>(a);
    out.addAll(b);
    return out;
  }
}
