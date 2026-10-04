package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.RULES;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.TRAVEL;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.reefer;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.vehicle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Pin;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.infrastructure.ImprovingEngine;
import com.waypoint.dispatch.planning.infrastructure.PriorityInsertionEngine;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A regenerate that keeps a dispatcher's decisions: the engine puts them back first, or refuses. */
class PinnedDecisionsTest {
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();
  static final PriorityPolicy POLICY = new PriorityPolicy(UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS);

  final FleetVehicle t1 = truck("T1");
  final FleetVehicle t2 = truck("T2");
  final FleetVehicle r1 = reefer("R1");
  final PlanOrder a = order().build();
  final PlanOrder b = order().build();
  final PlanOrder c = order().build();

  Problem problem(List<PlanOrder> orders, List<FleetVehicle> fleet) {
    return new Problem("Peliyagoda", LocalDate.of(2026, 10, 5), orders, fleet, TRAVEL, RULES, POLICY);
  }

  PriorityInsertionEngine engine() {
    return new PriorityInsertionEngine(REGISTRY);
  }

  @Test
  void aPinnedOrderGoesBackWhereItWasWhateverTheEngineWouldHaveChosen() {
    Problem free = problem(List.of(a, b, c), List.of(t1, t2));
    AllocationResult unpinned = engine().allocate(free);
    String chosen = unpinned.decisionFor(c.orderId()).orElseThrow().vehicleId().orElseThrow();
    String elsewhere = chosen.equals("T1") ? "T2" : "T1";

    AllocationResult pinned =
        engine().allocate(free.keeping(Map.of(c.orderId(), new Pin(elsewhere, 1)), Set.of()));

    OrderDecision d = pinned.decisionFor(c.orderId()).orElseThrow();
    assertEquals(AllocationDecision.SERVED, d.decision());
    assertEquals(Optional.of(elsewhere), d.vehicleId(), "kept where the dispatcher put it, not where the engine would");
    assertEquals(Optional.of(1), d.tripNumber());
  }

  @Test
  void aPinThatNoLongerPassesTheRulesRefusesTheWholeRunAndNamesTheRule() {
    PlanOrder chilled = order().chilled().build();
    Problem p = problem(List.of(a, chilled), List.of(t1, r1)).keeping(Map.of(chilled.orderId(), new Pin("T1", 1)), Set.of());

    DomainException refused = assertThrows(DomainException.class, () -> engine().allocate(p));

    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertTrue(refused.rules().contains("R-PLN-02"), refused.rules().toString());
    assertTrue(refused.getMessage().contains(chilled.orderRef()), "names the order: " + refused.getMessage());
  }

  @Test
  void aPinOnAVehicleThatIsNotAvailableIsRefusedWithTheWorkshopRule() {
    FleetVehicle workshop = vehicle("T9", false, false, "5000", "30", false);
    Problem p = problem(List.of(a), List.of(t1, workshop)).keeping(Map.of(a.orderId(), new Pin("T9", 1)), Set.of());

    DomainException refused = assertThrows(DomainException.class, () -> engine().allocate(p));

    assertEquals(List.of("R-FLT-03"), refused.rules());
  }

  @Test
  void anOrderKeptDeferredIsNotOfferedAPlaceEvenWhenThereIsRoom() {
    Problem p = problem(List.of(a, b), List.of(t1, t2)).keeping(Map.of(), Set.of(b.orderId()));

    AllocationResult result = engine().allocate(p);

    OrderDecision held = result.decisionFor(b.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, held.decision());
    assertEquals(Optional.of("R-PLN-19"), held.bindingRule());
    assertEquals(AllocationDecision.SERVED, result.decisionFor(a.orderId()).orElseThrow().decision());
  }

  @Test
  void theSecondPassIsSkippedWhenDecisionsAreKeptSoItCannotMoveThem() {
    Problem p = problem(List.of(a, b), List.of(t1, t2)).keeping(Map.of(a.orderId(), new Pin("T2", 1)), Set.of());

    AllocationResult result = new ImprovingEngine(engine(), REGISTRY).allocate(p);

    assertTrue(result.improvement().isEmpty(), "no second pass ran, so none is claimed");
    assertEquals(Optional.of("T2"), result.decisionFor(a.orderId()).orElseThrow().vehicleId());
  }

  @Test
  void withNothingKeptTheSecondPassStillRuns() {
    Problem p = problem(List.of(a, b), List.of(t1, t2));

    AllocationResult result = new ImprovingEngine(engine(), REGISTRY).allocate(p);

    assertTrue(result.improvement().isPresent());
  }
}
