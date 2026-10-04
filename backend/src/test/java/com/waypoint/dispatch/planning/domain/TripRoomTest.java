package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #199, R-ORD-14: does one more order join a trip already going to a district? */
class TripRoomTest {
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();
  static final PlanContext CONTEXT = new PlanContext("D1", Map.of(),
      new RuleSet(UUID.randomUUID(), Map.of(RuleSet.CAPACITY_EPSILON, new BigDecimal("0.000001"))));

  static FleetVehicle vehicle(String id, String m3, boolean van, boolean reefer, boolean available) {
    return new FleetVehicle(id, "D1", van, reefer, new BigDecimal("5000"), new BigDecimal(m3), BigDecimal.TEN,
        new BigDecimal("500"), available, BigDecimal.ZERO);
  }

  static PlanOrder order(String m3, String temperature, boolean vanOnly) {
    return new PlanOrder(UUID.randomUUID(), "R", "OUT", "D1", "Tech", "Kandy", temperature, new BigDecimal("100"),
        new BigDecimal(m3), "standard", vanOnly, false, Optional.empty(), Optional.empty(), BigDecimal.ZERO, 0, 0,
        LocalDate.of(2026, 10, 9));
  }

  @Test
  void anOrderJoinsWhenTheTripGoingThereHasRoom() {
    List<FleetVehicle> fleet = List.of(vehicle("T1", "20", false, false, true));
    assertTrue(TripRoom.joins(fleet, List.of(order("12", "ambient", false)), order("6", "ambient", false), CONTEXT, REGISTRY));
  }

  @Test
  void anOrderThatWouldNeedATripOfItsOwnDoesNotJoin() {
    List<FleetVehicle> fleet = List.of(vehicle("T1", "20", false, false, true), vehicle("T2", "20", false, false, true));
    assertFalse(TripRoom.joins(fleet, List.of(order("18", "ambient", false)), order("6", "ambient", false), CONTEXT, REGISTRY),
        "18 + 6 is over 20; a second vehicle would be a second trip");
  }

  @Test
  void withNothingBookedThereIsNoTripToJoin() {
    assertFalse(TripRoom.joins(List.of(vehicle("T1", "20", false, false, true)), List.of(), order("1", "ambient", false),
        CONTEXT, REGISTRY));
  }

  @Test
  void aVehicleInTheWorkshopCarriesNothing() {
    List<FleetVehicle> fleet = List.of(vehicle("BIG", "40", false, false, false), vehicle("T1", "20", false, false, true));
    assertFalse(TripRoom.joins(fleet, List.of(order("18", "ambient", false)), order("6", "ambient", false), CONTEXT, REGISTRY),
        "the 40 m3 truck is unavailable that day");
  }

  @Test
  void aVanOnlyStoreJoinsOnlyATripOnAVan() {
    List<FleetVehicle> trucks = List.of(vehicle("T1", "20", false, false, true));
    assertFalse(TripRoom.joins(trucks, List.of(order("4", "ambient", false)), order("2", "ambient", true), CONTEXT, REGISTRY));
    List<FleetVehicle> vans = List.of(vehicle("V1", "10", true, false, true));
    assertTrue(TripRoom.joins(vans, List.of(order("4", "ambient", false)), order("2", "ambient", true), CONTEXT, REGISTRY));
  }

  @Test
  void theBookedLoadIsPackedLikeTripsSoRoomOnAnyTripCounts() {
    List<FleetVehicle> fleet = List.of(vehicle("T1", "20", false, false, true), vehicle("T2", "20", false, false, true));
    // 15 and 15 take two trips; the second has 5 left.
    assertTrue(TripRoom.joins(fleet, List.of(order("15", "ambient", false), order("15", "ambient", false)),
        order("5", "ambient", false), CONTEXT, REGISTRY));
  }
}
