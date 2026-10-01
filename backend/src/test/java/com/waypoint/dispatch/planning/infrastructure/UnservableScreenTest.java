package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PLN-09: a chilled order for a van-only outlet, larger than every reefer van,
 * is unservable and names the rule, even though a reefer truck could carry it.
 * The truck is the wrong kind for the outlet, so no run will ever serve it.
 */
class UnservableScreenTest {

  @Test
  void aChilledVanOnlyOrderBiggerThanEveryReeferVanIsUnservable() {
    PlanOrder order =
        new PlanOrder(
            UUID.randomUUID(), "PLN-09", "OUT-VAN", "Peliyagoda", "Fresh", "Colombo", "chilled",
            new BigDecimal("300"), new BigDecimal("9.5"), "street", true, false,
            Optional.of(LocalTime.of(3, 0)), Optional.of(LocalTime.of(8, 0)), new BigDecimal("15"), 0, 1,
            LocalDate.of(2026, 10, 5));
    List<FleetVehicle> fleet =
        List.of(
            vehicle("REEFER-VAN", true, true, "900", "7"),
            vehicle("REEFER-TRUCK", false, true, "5000", "30"),
            vehicle("VAN", true, false, "900", "12"));
    Problem problem =
        new Problem(
            "Peliyagoda", LocalDate.of(2026, 10, 5), List.of(order), fleet,
            Map.of("Colombo", new DistrictTravel("Colombo", new BigDecimal("24"), new BigDecimal("8"),
                new BigDecimal("12"), new BigDecimal("4"))),
            new RuleSet(UUID.randomUUID(), RuleSet.bookletParameters()),
            new PriorityPolicy(UUID.randomUUID(), PriorityPolicy.DEFAULT_KEYS));

    OrderDecision decision =
        new PriorityInsertionEngine(ConstraintRegistry.standard()).allocate(problem).decisionFor(order.orderId()).orElseThrow();

    assertEquals(AllocationDecision.UNSERVABLE, decision.decision(), "never a deferral that repeats forever");
    assertEquals(Optional.of("R-PLN-06"), decision.bindingRule(), "the reefer van is too small: " + decision.reason());
  }

  private static FleetVehicle vehicle(String id, boolean van, boolean reefer, String kg, String m3) {
    return new FleetVehicle(
        id, "Peliyagoda", van, reefer, new BigDecimal(kg), new BigDecimal(m3), new BigDecimal("5"),
        new BigDecimal("400"), true, BigDecimal.ZERO);
  }
}
