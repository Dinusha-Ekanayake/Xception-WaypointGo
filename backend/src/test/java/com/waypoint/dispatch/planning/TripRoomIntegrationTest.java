package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.LoadView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Issue #199, R-ORD-14: Planning answers whether one more order joins a trip
 * already going to a district, on the depot's real fleet and rule set.
 */
class TripRoomIntegrationTest extends PlanningIntegrationSupport {
  @Autowired PlanQuery plans;

  static LoadView ambient(BigDecimal m3) {
    return new LoadView("ambient", false, new BigDecimal("50.000"), m3);
  }

  BigDecimal largestTruckM3() {
    return reference.availableVehicles(depot, serviceDate, null).stream()
        .filter(v -> !v.refrigerated())
        .map(VehicleView::volumeCapM3).max(Comparator.naturalOrder()).orElseThrow();
  }

  @Test
  void aSmallOrderJoinsATripThatHasRoom() {
    assertTrue(plans.joinsTrip(depot, serviceDate, "Tech", "Colombo",
        List.of(ambient(new BigDecimal("1.0000"))), ambient(new BigDecimal("0.5000"))));
  }

  @Test
  void anOrderThatWouldOverfillEveryTripGoingThereDoesNotJoin() {
    BigDecimal full = largestTruckM3().subtract(new BigDecimal("0.1000"));
    assertFalse(plans.joinsTrip(depot, serviceDate, "Tech", "Colombo",
        List.of(ambient(full)), ambient(new BigDecimal("1.0000"))),
        "the trip there is all but full, so this order would need a trip of its own");
  }

  @Test
  void withNothingBookedThereIsNoTripToJoin() {
    assertFalse(plans.joinsTrip(depot, serviceDate, "Tech", "Colombo", List.of(), ambient(BigDecimal.ONE)));
  }
}
