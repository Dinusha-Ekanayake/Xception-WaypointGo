package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.RULES;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.TRAVEL;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.oneTrip;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Pin;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The cost stage (planning v2, R-PLN-38, R-PLN-39) on hand-built days: it puts
 * the same orders on fewer vehicles, never changes which orders are served,
 * leaves a simple day or a dispatcher's kept decisions alone, and repeats.
 */
class CostReplanTest {
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();
  static final PriorityPolicy POLICY = new PriorityPolicy(UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS);
  static final FleetVehicle T1 = truck("T1");
  static final FleetVehicle T2 = truck("T2");
  static final FleetVehicle T3 = truck("T3");

  static Problem problem(List<PlanOrder> orders, RuleSet rules) {
    return new Problem("Peliyagoda", LocalDate.of(2026, 10, 5), orders, List.of(T1, T2, T3), TRAVEL, rules, POLICY);
  }

  /** The rules plan as given: each order alone on its own truck, all served. */
  static AllocationResult spread(List<PlanOrder> orders) {
    List<FleetVehicle> trucks = List.of(T1, T2, T3);
    List<VehicleDay> days = new ArrayList<>();
    List<OrderDecision> decisions = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
      VehicleDay d = oneTrip(trucks.get(i), orders.get(i));
      days.add(d);
      decisions.add(new OrderDecision(orders.get(i).orderId(), AllocationDecision.SERVED, Optional.of(d.vehicleId()),
          Optional.of(1), Optional.empty(), "rules", List.of()));
    }
    return new AllocationResult(days, decisions, false, "rules");
  }

  /** Three small Colombo orders, one per truck: a plan that leaves the trucks nearly empty. */
  static List<PlanOrder> threeSmall() {
    return List.of(
        order().ref("A").volume("2").build(),
        order().ref("B").volume("2").build(),
        order().ref("C").volume("2").build());
  }

  static Set<UUID> served(AllocationResult r) {
    return r.decisions().stream().filter(d -> d.decision() == AllocationDecision.SERVED)
        .map(OrderDecision::orderId).collect(Collectors.toSet());
  }

  static CostReplan.Result run(Problem p, AllocationResult rules) {
    return new CostReplan(REGISTRY).improve(p, rules, () -> 0L, Long.MAX_VALUE, "test");
  }

  @Test
  void halfEmptyTrucksAreMergedAndTheSameOrdersStayServed() {
    List<PlanOrder> orders = threeSmall();
    AllocationResult rules = spread(orders);
    CostReplan.Result result = run(problem(orders, RULES), rules);

    assertEquals(CostReplan.Trigger.LOW_UTILISATION, result.summary().trigger());
    assertTrue(result.summary().improved());
    assertEquals(3, result.summary().rulesVehicles());
    assertEquals(1, result.summary().vehicles(), "three 2 m3 orders of one brand and district fit one trip");
    assertTrue(result.summary().litres().compareTo(result.summary().rulesLitres()) < 0);
    assertEquals(served(rules), served(result.allocation()), "R-PLN-38: the served set never changes for cost");
    assertSame(rules, result.allocation().alternative().orElseThrow(), "the rules plan is offered beside it");
    for (VehicleDay d : result.allocation().days()) {
      assertTrue(ConstraintRegistry.allPass(REGISTRY.evaluate(new Constraint.Candidate(d, problem(orders, RULES).context(), Set.of()))));
    }
  }

  @Test
  void aSimpleDayKeepsTheRulesPlan() {
    List<PlanOrder> orders = threeSmall();
    AllocationResult rules = spread(orders);
    Map<String, BigDecimal> p = new HashMap<>(RuleSet.bookletParameters());
    p.put(CostReplan.MIN_UTILISATION_KEY, BigDecimal.ZERO);
    CostReplan.Result result = run(problem(orders, new RuleSet(UUID.randomUUID(), p)), rules);

    assertEquals(CostReplan.Trigger.SKIPPED_SIMPLE_DAY, result.summary().trigger(), "nothing deferred, trips full enough");
    assertSame(rules, result.allocation());
    assertEquals(0, result.summary().iterations());
  }

  @Test
  void aDispatchersKeptDecisionsAreNotMovedAround() {
    List<PlanOrder> orders = threeSmall();
    AllocationResult rules = spread(orders);
    Problem kept = problem(orders, RULES).keeping(Map.of(orders.get(0).orderId(), new Pin("T1", 1)), Set.of());
    CostReplan.Result result = run(kept, rules);

    assertEquals(CostReplan.Trigger.SKIPPED_KEPT_DECISIONS, result.summary().trigger());
    assertSame(rules, result.allocation());
  }

  @Test
  void theStageCanBeSwitchedOffAsRuleSetData() {
    List<PlanOrder> orders = threeSmall();
    Map<String, BigDecimal> p = new HashMap<>(RuleSet.bookletParameters());
    p.put(CostReplan.ENABLED_KEY, BigDecimal.ZERO);
    CostReplan.Result result = run(problem(orders, new RuleSet(UUID.randomUUID(), p)), spread(orders));
    assertEquals(CostReplan.Trigger.SKIPPED_DISABLED, result.summary().trigger());
  }

  @Test
  void theSameOrdersGiveTheSamePlan() {
    List<PlanOrder> orders = threeSmall();
    CostReplan.Result first = run(problem(orders, RULES), spread(orders));
    CostReplan.Result second = run(problem(orders, RULES), spread(orders));
    assertEquals(first.allocation().days(), second.allocation().days());
    assertEquals(first.summary(), second.summary());
  }

  @Test
  void noIterationsKeepsTheRulesPlan() {
    List<PlanOrder> orders = threeSmall();
    AllocationResult rules = spread(orders);
    CostReplan.Result result = new CostReplan(REGISTRY, 0).improve(problem(orders, RULES), rules, () -> 0L, Long.MAX_VALUE, "test");
    assertFalse(result.summary().improved());
    assertSame(rules, result.allocation());
  }

  @Test
  void theClockStopsTheSearchAndSaysSo() {
    List<PlanOrder> orders = threeSmall();
    AllocationResult rules = spread(orders);
    CostReplan.Result result = new CostReplan(REGISTRY).improve(problem(orders, RULES), rules, () -> 10L, 5L, "test");
    assertEquals(CostReplan.Stop.CLOCK, result.summary().stoppedBy());
    assertSame(rules, result.allocation(), "stopped before the first iteration: the rules plan stands");
  }
}
