package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.candidate;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.oneTrip;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.reefer;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.van;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.vehicle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One pass, one fail and the slack for every constraint in the registry. */
class ConstraintsTest {

  static ConstraintResult check(Constraint c, VehicleDay day) {
    return c.check(candidate(day));
  }

  @Test
  void vehicleAvailable() {
    assertTrue(check(new Constraints.VehicleAvailable(), oneTrip(truck("V1"), order().build())).passed());
    ConstraintResult r =
        check(new Constraints.VehicleAvailable(), oneTrip(vehicle("V2", false, false, "5000", "30", false), order().build()));
    assertFalse(r.passed());
    assertEquals("R-FLT-03", r.ruleId());
  }

  @Test
  void homeDepotRejectsAnotherDepotsOutlet() {
    assertTrue(check(new Constraints.HomeDepot(), oneTrip(truck("V1"), order().build())).passed());
    ConstraintResult r = check(new Constraints.HomeDepot(), oneTrip(truck("V1"), order().depot("Kandy").build()));
    assertFalse(r.passed());
    assertEquals("R-PLN-04", r.ruleId());
  }

  @Test
  void singleBrandDistrict() {
    Trip mixed = Trip.of(order().build()).with(order().district("Gampaha").build());
    VehicleDay day = new VehicleDay(truck("V1"), java.util.List.of(mixed));
    assertFalse(check(new Constraints.SingleBrandDistrict(), day).passed());
    assertTrue(check(new Constraints.SingleBrandDistrict(), oneTrip(truck("V1"), order().build(), order().build())).passed());
  }

  @Test
  void singleTemperaturePerTrip() {
    Trip mixed = Trip.of(order().chilled().build()).with(order().build());
    assertFalse(check(new Constraints.SingleTemperaturePerTrip(), new VehicleDay(reefer("R1"), java.util.List.of(mixed))).passed());
    VehicleDay twoTrips = oneTrip(reefer("R1"), order().chilled().build()).withNewTrip(order().build());
    assertTrue(check(new Constraints.SingleTemperaturePerTrip(), twoTrips).passed());
  }

  @Test
  void chilledNeedsAReeferAndFrozenCountsAsChilled() {
    assertFalse(check(new Constraints.Temperature(), oneTrip(truck("V1"), order().chilled().build())).passed());
    ConstraintResult frozen = check(new Constraints.Temperature(), oneTrip(truck("V1"), order().temperature("frozen").build()));
    assertFalse(frozen.passed());
    assertEquals("R-PLN-02", frozen.ruleId());
    assertTrue(check(new Constraints.Temperature(), oneTrip(reefer("R1"), order().chilled().build())).passed());
    assertTrue(check(new Constraints.Temperature(), oneTrip(reefer("R1"), order().build())).passed());
  }

  @Test
  void vanOnlyOutletNeedsAVan() {
    assertFalse(check(new Constraints.VanOnlyAccess(), oneTrip(truck("V1"), order().vanOnly().build())).passed());
    assertTrue(check(new Constraints.VanOnlyAccess(), oneTrip(van("V2"), order().vanOnly().build())).passed());
  }

  @Test
  void wholeOrderRejectsAnOrderAlreadyOnAnotherVehicle() {
    PlanOrder o = order().build();
    VehicleDay day = oneTrip(truck("V1"), o);
    assertTrue(new Constraints.WholeOrder().check(candidate(day)).passed());
    ConstraintResult r =
        new Constraints.WholeOrder().check(new Constraint.Candidate(day, PlanningFixtures.CONTEXT, Set.of(o.orderId())));
    assertFalse(r.passed());
    assertEquals("R-PLN-05", r.ruleId());
  }

  @Test
  void capacityUsesTheValidatorsEpsilonAtTheBoundary() {
    VehicleDay atCapPlusTolerance = oneTrip(truck("V1"), order().volume("30.0000009").build());
    ConstraintResult inside = check(new Constraints.VolumeCapacity(), atCapPlusTolerance);
    assertTrue(inside.passed(), "within 1e-6 of the cap fits, as check_allocation.py allows (PLN-15)");

    VehicleDay over = oneTrip(truck("V1"), order().volume("30.000002").build());
    ConstraintResult r = check(new Constraints.VolumeCapacity(), over);
    assertFalse(r.passed());
    assertEquals(0, new BigDecimal("-0.000002").compareTo(r.slack().orElseThrow()));
  }

  @Test
  void weightCapacityReportsSlack() {
    ConstraintResult r = check(new Constraints.WeightCapacity(), oneTrip(truck("V1"), order().weight("4200").build()));
    assertTrue(r.passed());
    assertEquals(0, new BigDecimal("800").compareTo(r.slack().orElseThrow()));
    assertFalse(check(new Constraints.WeightCapacity(), oneTrip(truck("V1"), order().weight("5001").build())).passed());
  }

  @Test
  void atMostTwoTripsPerVehicle() {
    VehicleDay three = oneTrip(truck("V1"), order().build()).withNewTrip(order().build()).withNewTrip(order().build());
    ConstraintResult r = check(new Constraints.TripCount(), three);
    assertFalse(r.passed());
    assertEquals(0, BigDecimal.valueOf(-1).compareTo(r.slack().orElseThrow()));
  }

  @Test
  void freshAndDaytimeBudgetsAreSeparate() {
    VehicleDay puttalamTwice =
        oneTrip(truck("V1"), order().district("Puttalam").build()).withNewTrip(order().district("Puttalam").build());
    ConstraintResult fresh = check(new Constraints.TimeBudget(), puttalamTwice);
    assertFalse(fresh.passed(), "173 + 16 twice is 378 min, over the 270 min Fresh budget");
    assertEquals("R-PLN-09", fresh.ruleId());

    VehicleDay freshThenStyle =
        oneTrip(truck("V1"), order().district("Puttalam").build())
            .withNewTrip(order().brand("Style").district("Puttalam").dock("rear_dock").build());
    assertTrue(check(new Constraints.TimeBudget(), freshThenStyle).passed());
  }

  @Test
  void deliveryWindowNamesTheLateStop() {
    ConstraintResult r =
        check(new Constraints.DeliveryWindow(), oneTrip(truck("V1"), order().district("Puttalam").window("03:00", "06:00").build()));
    assertFalse(r.passed(), "Puttalam is 173 min out, so 03:30 departure arrives at 06:23");
    assertEquals("R-PLN-13", r.ruleId());
    assertEquals(0, new BigDecimal("-23").compareTo(r.slack().orElseThrow()));
  }

  @Test
  void emptyMallIntersectionIsUnservableNotLate() {
    ConstraintResult r = check(new Constraints.DeliveryWindow(), oneTrip(truck("V1"), order().mall().noWindow().build()));
    assertFalse(r.passed());
    assertEquals("R-PLN-29", r.ruleId());
  }

  @Test
  void windowShorterThanServiceIsUnservable() {
    ConstraintResult r =
        check(new Constraints.DeliveryWindow(), oneTrip(truck("V1"), order().window("05:00", "05:10").build()));
    assertFalse(r.passed());
    assertEquals("R-PLN-30", r.ruleId());
  }

  @Test
  void fuelIncludesTheReturnLegAndOtherPublishedPlans() {
    FleetVehicle nearlyEmpty =
        new FleetVehicle("V1", "Peliyagoda", false, false, new BigDecimal("5000"), new BigDecimal("30"), new BigDecimal("5"), new BigDecimal("100"), true, new BigDecimal("50"));
    VehicleDay puttalam = oneTrip(nearlyEmpty, order().district("Puttalam").build());
    ConstraintResult r = check(new Constraints.FuelQuota(), puttalam);
    assertFalse(r.passed(), "130 km out and back is 52 L at 5 km/L, and only 50 L remain");
    assertEquals(0, new BigDecimal("-2").compareTo(r.slack().orElseThrow()));

    assertTrue(check(new Constraints.FuelQuota(), oneTrip(truck("V2"), order().build())).passed());
  }

  @Test
  void aMissingRuleParameterRefusesRatherThanDefaulting() {
    Map<String, BigDecimal> params = new HashMap<>(RuleSet.bookletParameters());
    params.remove(RuleSet.FRESH_BUDGET_MIN);
    RuleSet incomplete = new RuleSet(UUID.randomUUID(), params);
    DomainException e = assertThrows(DomainException.class, incomplete::freshBudgetMinutes);
    assertEquals(java.util.List.of("POL-10"), e.violations());
  }

  @Test
  void registryReportsTheFirstFailureAsBinding() {
    VehicleDay day = oneTrip(truck("V1"), order().chilled().vanOnly().build());
    var results = ConstraintRegistry.standard().evaluate(candidate(day));
    assertEquals("R-PLN-02", ConstraintRegistry.firstFailure(results).orElseThrow().ruleId());
    assertEquals(2, ConstraintRegistry.failures(results));
  }
}
