package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.RULES;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.TRAVEL;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.reefer;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The scarce-fleet re-plan (issue #92, R-PLN-32) on hand-built days: it repairs
 * a reefer spent badly, leaves a good plan alone, never trades a higher ranked
 * order for lower ranked ones, and is deterministic and bounded.
 */
class ScarceFleetReplanTest {
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();
  static final PriorityPolicy POLICY = new PriorityPolicy(UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS);
  static final FleetVehicle R1 = reefer("R1");
  static final FleetVehicle T1 = truck("T1");

  static Problem problem(List<PlanOrder> orders) {
    return new Problem("Peliyagoda", LocalDate.of(2026, 10, 5), orders, List.of(R1, T1), TRAVEL, RULES, POLICY);
  }

  /** A first plan as given: these orders on R1, one trip each in the order listed; everything else deferred. */
  static AllocationResult firstPlan(List<PlanOrder> orders, List<List<PlanOrder>> reeferTrips) {
    List<Trip> trips = new ArrayList<>();
    for (List<PlanOrder> t : reeferTrips) {
      Trip trip = Trip.of(t.get(0));
      for (PlanOrder o : t.subList(1, t.size())) {
        trip = trip.with(o);
      }
      trips.add(trip);
    }
    VehicleDay day = new VehicleDay(R1, trips);
    List<OrderDecision> decisions = new ArrayList<>();
    for (PlanOrder o : orders) {
      Optional<Integer> trip = day.tripNumberOf(o.orderId());
      decisions.add(
          trip.isPresent()
              ? new OrderDecision(o.orderId(), AllocationDecision.SERVED, Optional.of("R1"), trip, Optional.empty(), "first pass", List.of())
              : CheapestInsertion.deferred(o, "R-PLN-09", "first pass", List.of()));
    }
    return new AllocationResult(List.of(day), decisions, false, "first");
  }

  static ScarceFleetReplan.Result improve(Problem p, AllocationResult first, long maxNodes) {
    return new ScarceFleetReplan(REGISTRY, maxNodes).improve(p, first, () -> 0L, Long.MAX_VALUE, "first+replan");
  }

  static Set<UUID> served(AllocationResult r) {
    return r.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.SERVED)
        .map(OrderDecision::orderId)
        .collect(Collectors.toSet());
  }

  static void assertFeasible(Problem p, AllocationResult r) {
    Set<UUID> demand = p.orders().stream().map(PlanOrder::orderId).collect(Collectors.toSet());
    assertEquals(List.of(), PlanVerification.verify(r, demand, p.context(), REGISTRY), "PLN-12 would accept it");
  }

  @Test
  void aReeferSpentOnALowRankedFarLoadIsPlannedAgainForTheEarlyClosingOnes() {
    PlanOrder far = order().chilled().district("Puttalam").volume("2").build();
    PlanOrder c1 = order().chilled().district("Kurunegala").window("05:00", "07:30").volume("8").build();
    PlanOrder c2 = order().chilled().district("Kurunegala").window("05:00", "07:30").volume("8").build();
    List<PlanOrder> orders = List.of(far, c1, c2);
    Problem p = problem(orders);
    AllocationResult first = firstPlan(orders, List.of(List.of(far)));

    ScarceFleetReplan.Result r = improve(p, first, ScarceFleetReplan.MAX_NODES);

    assertTrue(r.summary().improved());
    assertTrue(served(r.allocation()).containsAll(Set.of(c1.orderId(), c2.orderId())), "both close at 07:30, so both outrank it");
    assertEquals(1, r.summary().greedyServed());
    assertEquals(2, r.summary().served());
    assertEquals("first+replan", r.allocation().engine());
    assertFeasible(p, r.allocation());
    OrderDecision left = r.allocation().decisionFor(far.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, left.decision());
    assertEquals("R-PLN-09", left.bindingRule().orElseThrow(), "explained against the final plan: the Fresh budget is spent (R-PLN-19)");
  }

  @Test
  void aHigherRankedOrderIsNeverDeferredToServeTwoLowerOnes() {
    PlanOrder skipped = order().chilled().district("Puttalam").deferrals(1).build();
    PlanOrder l1 = order().chilled().district("Kurunegala").build();
    PlanOrder l2 = order().chilled().district("Kurunegala").build();
    List<PlanOrder> orders = List.of(skipped, l1, l2);
    Problem p = problem(orders);
    AllocationResult first = firstPlan(orders, List.of(List.of(skipped)));

    ScarceFleetReplan.Result r = improve(p, first, ScarceFleetReplan.MAX_NODES);

    assertFalse(r.summary().improved(), "two served instead of one is worse here: the one skipped yesterday ranks first (R-PLN-21)");
    assertSame(first, r.allocation());
    assertTrue(served(r.allocation()).contains(skipped.orderId()));
  }

  @Test
  void aPlanAlreadyBestByRankIsLeftAsItIs() {
    PlanOrder a = order().chilled().district("Colombo").build();
    PlanOrder b = order().chilled().district("Colombo").build();
    List<PlanOrder> orders = List.of(a, b);
    Problem p = problem(orders);
    AllocationResult first = firstPlan(orders, List.of(List.of(a, b)));

    ScarceFleetReplan.Result r = improve(p, first, ScarceFleetReplan.MAX_NODES);

    assertSame(first, r.allocation());
    assertFalse(r.summary().improved());
    assertEquals(ScarceFleetReplan.Stop.NONE, r.summary().stoppedBy());
  }

  @Test
  void theSameProblemGivesTheSamePlan() {
    PlanOrder far = order().chilled().district("Puttalam").volume("2").build();
    PlanOrder c1 = order().chilled().district("Kurunegala").window("05:00", "07:30").volume("8").build();
    PlanOrder c2 = order().chilled().district("Gampaha").window("05:00", "07:30").volume("6").build();
    PlanOrder c3 = order().chilled().district("Colombo").window("05:00", "07:45").volume("4").build();
    List<PlanOrder> orders = List.of(far, c1, c2, c3);
    Problem p = problem(orders);
    AllocationResult first = firstPlan(orders, List.of(List.of(far)));

    AllocationResult once = improve(p, first, ScarceFleetReplan.MAX_NODES).allocation();
    AllocationResult twice = improve(p, first, ScarceFleetReplan.MAX_NODES).allocation();

    assertEquals(once.decisions(), twice.decisions());
    assertEquals(once.days(), twice.days());
  }

  @Test
  void withNoBudgetTheFirstPlanStandsAndSaysWhy() {
    PlanOrder far = order().chilled().district("Puttalam").volume("2").build();
    PlanOrder c1 = order().chilled().district("Kurunegala").window("05:00", "07:30").volume("8").build();
    List<PlanOrder> orders = List.of(far, c1);
    Problem p = problem(orders);
    AllocationResult first = firstPlan(orders, List.of(List.of(far)));

    ScarceFleetReplan.Result r = improve(p, first, 0);

    assertSame(first, r.allocation(), "never worse than the first pass, however little time it had");
    assertEquals(ScarceFleetReplan.Stop.NODES, r.summary().stoppedBy());
  }

  @Test
  void plansAreComparedByTheHighestRankedOrderOnlyOneServes() {
    PlanOrder high = order().deferrals(1).build();
    PlanOrder low1 = order().build();
    PlanOrder low2 = order().build();
    List<PlanOrder> ranked = POLICY.rank(List.of(high, low1, low2), PlanningFixtures.CONTEXT);

    assertTrue(ScarceFleetReplan.compareByRank(ranked, Set.of(high.orderId()), Set.of(low1.orderId(), low2.orderId())) > 0);
    assertEquals(0, ScarceFleetReplan.compareByRank(ranked, Set.of(low1.orderId()), Set.of(low1.orderId())));
  }
}
