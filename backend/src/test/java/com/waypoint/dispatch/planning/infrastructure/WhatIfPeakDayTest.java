package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.WhatIf;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Roadmap AI-1 on the Task 2B peak day: each refrigerated vehicle in the
 * workshop, back for the day, by two runs of the same engine.
 */
class WhatIfPeakDayTest {
  @Test
  void aReeferBackFromTheWorkshopServesMoreAndTheDifferenceIsListed() {
    PeakDayScenario s1 = PeakDayScenario.load(PeakDayAllocationTest.DATA, PeakDayAllocationTest.RULES, PeakDayAllocationTest.POLICY);
    Problem problem = s1.problem();
    var before = PeakDayAllocationTest.engine().allocate(problem);
    List<FleetVehicle> reefersOut = problem.fleet().stream().filter(v -> v.reefer() && !v.available()).toList();
    assertEquals(5, reefersOut.size(), "S1 has five reefers in the workshop");
    for (FleetVehicle back : reefersOut) {
      List<FleetVehicle> fleet = problem.fleet().stream()
          .map(v -> v == back ? new FleetVehicle(v.vehicleId(), v.depotCode(), v.van(), v.reefer(), v.weightCapKg(),
              v.volumeCapM3(), v.kmPerL(), v.weeklyFuelQuotaL(), true, v.fuelUsedThisWeekL()) : v)
          .toList();
      var after = PeakDayAllocationTest.engine().allocate(new Problem(problem.depotCode(), problem.serviceDate(),
          problem.orders(), fleet, problem.travel(), problem.rules(), problem.policy(), problem.pins(), problem.held()));
      var w = WhatIf.compare(UUID.randomUUID(), back.vehicleId(), problem.orders(), before, after);
      assertEquals(w.servedAfter() - w.servedBefore(), w.nowServed().size() - w.nowDeferred().size());
      assertTrue(w.chilledM3After().compareTo(w.chilledM3Before()) >= 0, back.vehicleId() + " back never carries less chilled: " + w);
      System.out.println("AI-1 " + back.vehicleId() + " (" + (back.van() ? "van" : "truck") + " " + back.volumeCapM3()
          + " m3) back: served " + w.servedBefore() + " -> " + w.servedAfter() + ", chilled " + w.chilledM3Before()
          + " -> " + w.chilledM3After() + " m3, now served " + w.nowServed().stream().map(o -> o.orderRef()).toList()
          + ", now deferred " + w.nowDeferred().stream().map(o -> o.orderRef()).toList());
    }
  }
}
