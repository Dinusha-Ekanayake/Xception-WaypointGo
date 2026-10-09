package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.FitDiagnosis;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Roadmap AI-2 on the Task 2B peak day: every chilled order the engine deferred
 * is diagnosed against the engine's own places and swap check.
 */
class FitDiagnosisPeakDayTest {
  @Test
  void everyDeferredChilledOrderHasAClosestPlaceAndAnySwapOfferedPassesAndRanksBelowIt() {
    PeakDayScenario s1 = PeakDayScenario.load(PeakDayAllocationTest.DATA, PeakDayAllocationTest.RULES, PeakDayAllocationTest.POLICY);
    AllocationResult result = PeakDayAllocationTest.engine().allocate(s1.problem());
    PlanningRun run = PlanningRun.draft(UUID.randomUUID(), s1.problem().depotCode(), s1.problem().serviceDate(), 1,
        new PlanningRun.Stamps(UUID.randomUUID(), PeakDayAllocationTest.RULES.id(), PeakDayAllocationTest.POLICY.id()),
        Optional.empty(), "s1", result, UUID.randomUUID());
    ConstraintRegistry registry = ConstraintRegistry.standard();
    PlanContext context = s1.problem().context();
    Comparator<PlanOrder> rank = PeakDayAllocationTest.POLICY.comparator(context);
    int diagnosed = 0;
    int swaps = 0;
    for (PlanOrder o : s1.problem().orders()) {
      boolean deferredChilled = "chilled".equals(o.temperature())
          && result.decisionFor(o.orderId()).orElseThrow().decision() == AllocationDecision.DEFERRED;
      if (!deferredChilled) {
        continue;
      }
      var d = FitDiagnosis.diagnose(run, o, new ArrayList<>(s1.problem().fleet()), registry, context, rank);
      assertFalse(d.fits(), o.orderRef() + " was deferred, so no place passes every rule");
      assertTrue(d.closest().isPresent() && !FitDiagnosis.failed(d.closest().get()).isEmpty(), o.orderRef());
      if (d.swapOut().isPresent()) {
        swaps++;
        PlanOrder out = d.swapOut().get();
        assertTrue(rank.compare(out, o) > 0, out.orderRef() + " must rank below " + o.orderRef());
        assertTrue(run.proposeSwap(out, o, registry, context).feasible(), "the offered swap passes every rule");
      }
      diagnosed++;
      System.out.println("AI-2 " + o.orderRef() + ": closest " + d.closest().get().vehicleId() + " trip "
          + d.closest().get().tripNumber() + " breaks " + FitDiagnosis.failed(d.closest().get()).stream()
              .map(c -> c.ruleId()).toList() + (d.swapOut().map(s -> ", swap with " + s.orderRef()).orElse("")));
    }
    assertTrue(diagnosed == 10, "S1 defers 10 chilled orders under priority version 2: " + diagnosed);
    System.out.println("AI-2 swaps offered: " + swaps);
  }
}
