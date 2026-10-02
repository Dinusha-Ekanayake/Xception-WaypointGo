package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.CONTEXT;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.RULES;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.TRAVEL;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.candidate;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.domain.TripTimeline.TripSchedule;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The booklet's worked trip time examples, pages 20 and 21, and the stop clock. */
class TripTimelineTest {

  @Test
  void gampahaFreshTripWithThreeStopsIs101Minutes() {
    Trip trip =
        Trip.of(order().district("Gampaha").dock("rear_dock").build())
            .with(order().district("Gampaha").dock("rear_dock").build())
            .with(order().district("Gampaha").dock("street").build());

    assertEquals(0, new BigDecimal("101").compareTo(TripTimeline.formulaMinutes(trip, TRAVEL.get("Gampaha"))));
  }

  @Test
  void secondColomboTripMakesTheVehicleUse213OfIts270FreshMinutes() {
    VehicleDay day =
        PlanningFixtures.oneTrip(
            truck("VEH014"),
            order().district("Gampaha").dock("rear_dock").build(),
            order().district("Gampaha").dock("rear_dock").build(),
            order().district("Gampaha").dock("street").build());
    day = day.withNewTrip(order().build());
    for (int i = 0; i < 3; i++) {
      day = day.withJoined(2, order().build());
    }

    List<TripSchedule> schedules = TripTimeline.schedule(day, TRAVEL, RULES);
    assertEquals(0, new BigDecimal("112").compareTo(schedules.get(1).formulaMinutes()));

    ConstraintResult budget = new Constraints.TimeBudget().check(candidate(day));
    assertTrue(budget.passed());
    assertEquals(0, new BigDecimal("57").compareTo(budget.slack().orElseThrow()));
  }

  @Test
  void freshTripLeavesAt0330AndWaitsForAWindowToOpen() {
    PlanOrder late = order().window("05:00", "07:30").build();
    TripSchedule s = TripTimeline.schedule(PlanningFixtures.oneTrip(truck("V1"), late), TRAVEL, RULES).get(0);

    assertEquals(LocalTime.of(5, 0), s.stops().get(0).arrival());
    assertEquals(LocalTime.of(4, 36), s.departure());
  }

  @Test
  void secondTripLeavesWhenTheFirstTripsLastServiceEndsWithNoReturnLeg() {
    VehicleDay day = PlanningFixtures.oneTrip(truck("V1"), order().build());
    day = day.withNewTrip(order().build());

    List<TripSchedule> s = TripTimeline.schedule(day, TRAVEL, RULES);

    assertEquals(0, s.get(0).endMinute().compareTo(s.get(1).departureMinute()));
    assertEquals(LocalTime.of(4, 10), s.get(1).departure());
  }

  @Test
  void stopsAreSequencedSoEveryStopMakesItsWindow() {
    PlanOrder closesEarly = order().window("03:00", "04:00").build();
    PlanOrder opensEarly = order().window("03:00", "08:00").build();
    TripSchedule s =
        TripTimeline.schedule(PlanningFixtures.oneTrip(truck("V1"), opensEarly, closesEarly), TRAVEL, RULES).get(0);

    assertEquals(closesEarly.orderId(), s.stops().get(0).order().orderId());
    assertEquals(0, s.lateStops());
    assertTrue(new Constraints.DeliveryWindow().check(new Constraint.Candidate(
        PlanningFixtures.oneTrip(truck("V1"), opensEarly, closesEarly), CONTEXT, java.util.Set.of())).passed());
  }
}
