package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.RULES;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.TRAVEL;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Outlet GPS (R-PLN-40): exact points order a trip's stops by road and time the
 * drive between them; without points everything is the district's inter-stop
 * figure, exactly as before.
 */
class StopTravelTest {
  static final DistrictTravel COLOMBO = TRAVEL.get("Colombo");

  @Test
  void withoutExactPointsTheDistrictFiguresStand() {
    PlanOrder a = order().build();
    PlanOrder b = order().at(6.93, 79.85).build();
    assertEquals(COLOMBO.interStopMinutes(), StopTravel.minutes(a, b, COLOMBO));
    assertEquals(COLOMBO.interStopKm(), StopTravel.km(a, b, COLOMBO));
  }

  @Test
  void betweenTwoPointsTheDriveIsTheRoadDistanceAtTheDistrictsSpeed() {
    PlanOrder a = order().at(6.9000, 79.8500).build();
    PlanOrder b = order().at(6.9100, 79.8500).build();
    double straight = new Coordinates(6.90, 79.85).kmTo(new Coordinates(6.91, 79.85));
    assertEquals(1.112, straight, 0.01, "0.01 degree of latitude is about 1.11 km");
    double km = StopTravel.km(a, b, COLOMBO).doubleValue();
    assertEquals(straight * StopTravel.ROAD_FACTOR, km, 0.001);
    double perKm = COLOMBO.interStopMinutes().doubleValue() / COLOMBO.interStopKm().doubleValue();
    assertEquals(km * perKm, StopTravel.minutes(a, b, COLOMBO).doubleValue(), 0.01);
  }

  @Test
  void theShortestPathVisitsPointsAlongTheRoadNotInTheOrderGiven() {
    PlanOrder a = order().ref("A").at(6.90, 79.85).build();
    PlanOrder far = order().ref("FAR").at(6.96, 79.85).build();
    PlanOrder mid = order().ref("MID").at(6.92, 79.85).build();
    PlanOrder near = order().ref("NEAR").at(6.91, 79.85).build();
    List<PlanOrder> path = StopTravel.shortestPath(a, List.of(a, far, mid, near));
    assertEquals(List.of("A", "NEAR", "MID", "FAR"), path.stream().map(PlanOrder::orderRef).toList());
  }

  @Test
  void aTripOfLocatedStopsIsDrivenInRoadOrderWhenWindowsAllowIt() {
    // Same window for all: the window orders are arbitrary, the road order is not.
    PlanOrder a = order().ref("A").window("04:00", "09:00").at(6.90, 79.85).build();
    PlanOrder c = order().ref("C").window("04:00", "09:00").at(6.98, 79.85).build();
    PlanOrder b = order().ref("B").window("04:00", "09:00").at(6.91, 79.85).build();
    Trip trip = Trip.of(a).with(c).with(b);
    var schedule = TripTimeline.schedule(new VehicleDay(truck("T1"), List.of(trip)), TRAVEL, RULES).get(0);
    List<String> order = schedule.stops().stream().map(s -> s.order().orderRef()).toList();
    assertTrue(order.equals(List.of("A", "B", "C")) || order.equals(List.of("C", "B", "A")),
        "B lies between A and C, so it is visited between them: " + order);
    assertEquals(0, schedule.lateStops());
    // The booklet formula the budgets read is untouched by GPS.
    assertEquals(TripTimeline.formulaMinutes(trip, TRAVEL.get("Colombo")), schedule.formulaMinutes());
  }

  @Test
  void stopsWithoutPointsKeepTheWindowOrder() {
    PlanOrder late = order().ref("LATE").window("05:00", "09:00").build();
    PlanOrder early = order().ref("EARLY").window("04:00", "06:00").build();
    PlanOrder mid = order().ref("MID").window("04:30", "07:00").build();
    Trip trip = Trip.of(late).with(early).with(mid);
    var schedule = TripTimeline.schedule(new VehicleDay(truck("T1"), List.of(trip)), TRAVEL, RULES).get(0);
    assertEquals(List.of("EARLY", "MID", "LATE"), schedule.stops().stream().map(s -> s.order().orderRef()).toList());
    BigDecimal gap = schedule.stops().get(1).arrivalMinute().subtract(schedule.stops().get(0).serviceEndMinute());
    assertTrue(gap.compareTo(TRAVEL.get("Colombo").interStopMinutes()) >= 0, "the district's inter-stop minutes");
  }
}
