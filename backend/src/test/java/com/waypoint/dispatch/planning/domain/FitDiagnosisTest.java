package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.domain.PlanningRun.Placement;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap AI-2: the closest place for a deferred order, and who may make room. */
class FitDiagnosisTest {
  private static ConstraintResult pass(String rule) {
    return ConstraintResult.pass(rule, rule, "ok", BigDecimal.ONE);
  }

  private static ConstraintResult fail(String rule) {
    return ConstraintResult.fail(rule, rule, rule + " broken", BigDecimal.ONE.negate());
  }

  private static PlanOrder order(String ref, String m3) {
    return new PlanOrder(java.util.UUID.randomUUID(), ref, "OUT", "D1", "Fresh", "Colombo", "chilled",
        new BigDecimal("100"), new BigDecimal(m3), "street", false, false, java.util.Optional.empty(),
        java.util.Optional.empty(), BigDecimal.TEN, 0, 1, java.time.LocalDate.of(2026, 10, 9));
  }

  @Test
  void theClosestPlaceBreaksFewestRulesAndPrefersJoiningATrip() {
    Placement newTrip = new Placement("VEH001", 2, false, List.of(pass("R-PLN-06"), fail("R-PLN-07")));
    Placement joins = new Placement("VEH009", 1, true, List.of(fail("R-PLN-06"), pass("R-PLN-07")));
    Placement worse = new Placement("VEH002", 1, true, List.of(fail("R-PLN-06"), fail("R-PLN-09")));
    Placement closest = FitDiagnosis.closest(List.of(worse, newTrip, joins)).orElseThrow();
    assertEquals("VEH009", closest.vehicleId(), "one failure each; joining a trip wins the tie");
    assertEquals(List.of("R-PLN-06"), FitDiagnosis.failed(closest).stream().map(ConstraintResult::ruleId).toList());
    assertTrue(FitDiagnosis.closest(List.of()).isEmpty());
  }

  @Test
  void aPlaceThatBreaksOnlyAStructuralRuleIsNotCloserThanOneShortOfRoom() {
    // Joining another district's trip breaks one rule, but no room fixes it.
    Placement wrongDistrict = new Placement("VEH006", 1, true, List.of(fail("R-PLN-01"), pass("R-PLN-06")));
    Placement shortOfRoom = new Placement("VEH036", 2, true, List.of(pass("R-PLN-01"), fail("R-PLN-06"), fail("R-PLN-09")));
    assertEquals("VEH036", FitDiagnosis.closest(List.of(wrongDistrict, shortOfRoom)).orElseThrow().vehicleId());
  }

  @Test
  void onlyOrdersRankedBelowTheDeferredOneMayMakeRoomLowestFirst() {
    // Rank by volume, largest first: a smaller order ranks below.
    Comparator<PlanOrder> rank = Comparator.comparing(PlanOrder::volumeM3).reversed();
    PlanOrder deferred = order("D", "5");
    PlanOrder bigger = order("A", "9");
    PlanOrder small = order("B", "2");
    PlanOrder smaller = order("C", "1");
    Trip trip = new Trip("Fresh", "Colombo", TemperatureClass.CHILLED, List.of(bigger, small, smaller));
    assertEquals(List.of("C", "B"),
        FitDiagnosis.swapCandidates(trip, deferred, rank).stream().map(PlanOrder::orderRef).toList());
  }

  @Test
  void aHigherRankedOrderDeferredByHandIsOfferedTheSwapThatMakesRoom() {
    // One reefer van, one trip a day; the dispatcher deferred A (waited 3 days) and the trip carries B (1 day).
    var rules = new java.util.HashMap<>(RuleSet.bookletParameters());
    rules.put(RuleSet.MAX_TRIPS, BigDecimal.ONE);
    PlanContext context = new PlanContext("D1",
        java.util.Map.of("Colombo", new DistrictTravel("Colombo", new BigDecimal("24"), new BigDecimal("8"),
            new BigDecimal("12"), new BigDecimal("3"))),
        new RuleSet(java.util.UUID.randomUUID(), rules));
    FleetVehicle van = new FleetVehicle("V1", "D1", true, true, new BigDecimal("1040"), new BigDecimal("7"),
        BigDecimal.TEN, new BigDecimal("500"), true, BigDecimal.ZERO);
    PlanOrder a = order("A", "4", 3);
    PlanOrder b = order("B", "4", 1);
    VehicleDay day = new VehicleDay(van, List.of(Trip.of(b)));
    var decisions = List.of(
        new AllocationEngine.OrderDecision(b.orderId(), com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.SERVED,
            java.util.Optional.of("V1"), java.util.Optional.of(1), java.util.Optional.empty(), "", List.of()),
        new AllocationEngine.OrderDecision(a.orderId(), com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.DEFERRED,
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("R-PLN-19"), "kept off by hand", List.of()));
    PlanningRun run = PlanningRun.draft(java.util.UUID.randomUUID(), "D1", java.time.LocalDate.of(2026, 10, 9), 1,
        new PlanningRun.Stamps(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID()),
        java.util.Optional.empty(), "f",
        new AllocationEngine.AllocationResult(List.of(day), decisions, false, "test", java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty()),
        java.util.UUID.randomUUID());
    Comparator<PlanOrder> rank = new PriorityPolicy(java.util.UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS).comparator(context);

    var d = FitDiagnosis.diagnose(run, a, List.of(van), ConstraintRegistry.standard(), context, rank);
    assertTrue(!d.fits(), "8 m3 on a 7 m3 van, and no second trip");
    assertEquals("V1", d.closest().orElseThrow().vehicleId());
    assertEquals(List.of("R-PLN-06"), FitDiagnosis.failed(d.closest().get()).stream().map(ConstraintResult::ruleId).distinct().toList());
    assertEquals("B", d.swapOut().orElseThrow().orderRef(), "B ranks below A and its swap passes every rule");
  }

  private static PlanOrder order(String ref, String m3, int daysSince) {
    return new PlanOrder(java.util.UUID.randomUUID(), ref, "OUT-" + ref, "D1", "Fresh", "Colombo", "chilled",
        new BigDecimal("400"), new BigDecimal(m3), "street", false, false,
        java.util.Optional.of(java.time.LocalTime.of(5, 0)), java.util.Optional.of(java.time.LocalTime.of(8, 0)),
        new BigDecimal("16"), 0, daysSince, java.time.LocalDate.of(2026, 10, 9));
  }
}
