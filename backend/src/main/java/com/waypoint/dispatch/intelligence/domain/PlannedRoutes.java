package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A published trip as the delivery-risk model reads it: one route of legs,
 * each with its planned departure, travel and arrival.
 *
 * <p>Waypoint's timeline (R-PLN-08) leaves the depot, drives the district's
 * outbound time to the first stop, and the inter-stop time between stops, with
 * waiting and service in between. So a leg's planned departure is its arrival
 * minus its travel, which is the previous stop's planned finish. The model was
 * trained on routes planned the same way: departure equals the previous
 * arrival plus service, with waiting ignored by the planner.
 */
public final class PlannedRoutes {
  private PlannedRoutes() {}

  /** What an order contributes to a leg. */
  public record OrderFacts(
      LocalDate orderDate, boolean deferred, String temperature, int units, BigDecimal weightKg, BigDecimal volumeM3) {}

  /** @param sequence the plan's stop sequence, from 1 */
  public record StopFacts(
      int sequence,
      UUID orderId,
      String outletId,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<OrderFacts> order) {}

  /**
   * @param vehicleType {@code van} or {@code truck}
   * @param vehicleTemp {@code reefer} or {@code ambient}
   */
  public record TripFacts(
      UUID tripId,
      String vehicleId,
      String vehicleType,
      String vehicleTemp,
      String brandCode,
      String districtName,
      String depotCode,
      LocalDate serviceDate,
      BigDecimal outboundMinutes,
      BigDecimal interStopMinutes,
      BigDecimal outboundKm,
      BigDecimal interStopKm,
      List<StopFacts> stops) {

    public TripFacts {
      stops = List.copyOf(stops);
    }
  }

  /** @param seq 0 for the first stop, as the model numbers them */
  public record Leg(
      int seq,
      UUID orderId,
      String outletId,
      OrderFacts order,
      String fromPoint,
      BigDecimal distanceKm,
      LocalTime plannedDepart,
      BigDecimal plannedTravelMinutes,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose) {}

  public record Route(TripFacts trip, List<Leg> legs) {
    public Route {
      legs = List.copyOf(legs);
    }

    public String routeId() {
      return trip.tripId().toString();
    }
  }

  /**
   * The trip as a route, or empty when any stop lacks its order's size: the
   * model scores whole routes, so a route with a hole is left to the
   * deterministic estimator.
   */
  public static Optional<Route> route(TripFacts trip) {
    List<StopFacts> stops =
        trip.stops().stream().sorted(Comparator.comparingInt(StopFacts::sequence)).toList();
    if (stops.isEmpty() || stops.stream().anyMatch(s -> s.order().isEmpty())) {
      return Optional.empty();
    }
    List<Leg> legs = new ArrayList<>();
    String from = "DEPOT";
    for (int i = 0; i < stops.size(); i++) {
      StopFacts s = stops.get(i);
      boolean first = i == 0;
      BigDecimal travel = first ? trip.outboundMinutes() : trip.interStopMinutes();
      BigDecimal distance = first ? trip.outboundKm() : trip.interStopKm();
      long travelWhole = travel.setScale(0, RoundingMode.HALF_UP).longValue();
      legs.add(
          new Leg(
              i,
              s.orderId(),
              s.outletId(),
              s.order().orElseThrow(),
              from,
              distance,
              s.plannedArrival().minusMinutes(travelWhole),
              travel,
              s.plannedArrival(),
              s.windowOpen(),
              s.windowClose()));
      from = s.outletId();
    }
    return Optional.of(new Route(trip, legs));
  }
}
