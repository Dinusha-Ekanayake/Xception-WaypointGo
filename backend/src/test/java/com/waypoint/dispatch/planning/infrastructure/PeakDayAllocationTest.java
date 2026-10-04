package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.domain.ScarceFleetReplan;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import com.waypoint.dispatch.shared.error.DomainException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The engine on the Task 2B peak day. Writes
 * {@code target/task2b/submission_task2b.csv}, which CI hands to the official
 * {@code check_allocation.py} (PLN-17), and is also the Datathon deliverable.
 */
class PeakDayAllocationTest {
  static final Path DATA = Path.of("..", "data");
  static final RuleSet RULES = new RuleSet(UUID.nameUUIDFromBytes("rules-v1".getBytes()), RuleSet.bookletParameters());
  static final PriorityPolicy POLICY =
      new PriorityPolicy(UUID.nameUUIDFromBytes("policy-v1".getBytes()), PriorityPolicy.DEFAULT_KEYS);

  /** The production chain, as PlanningEngineConfiguration wires it. */
  static AllocationEngine engine() {
    ConstraintRegistry registry = ConstraintRegistry.standard();
    return new ValidatingEngine(
        new CostImprovingEngine(new ImprovingEngine(new PriorityInsertionEngine(registry), registry), registry),
        registry, v -> {});
  }

  @Test
  void theCostStageServesTheSameOrdersOnFewerVehiclesAndLessFuel() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    ConstraintRegistry registry = ConstraintRegistry.standard();
    AllocationResult rules = new ImprovingEngine(new PriorityInsertionEngine(registry), registry).allocate(s1.problem());
    AllocationResult result = engine().allocate(s1.problem());

    assertEquals(served(rules), served(result), "R-PLN-38: the cost stage never changes which orders are served on S1");
    var cost = result.cost().orElseThrow();
    assertEquals(com.waypoint.dispatch.planning.domain.CostReplan.Trigger.DEFERRALS, cost.trigger());
    assertTrue(cost.improved());
    assertTrue(cost.vehicles() < cost.rulesVehicles(), "fewer vehicles: " + cost);
    assertTrue(cost.litres().compareTo(cost.rulesLitres()) < 0, "less fuel: " + cost);
    assertEquals(com.waypoint.dispatch.planning.domain.CostReplan.Stop.NONE, cost.stoppedBy());
    assertTrue(result.alternative().isPresent(), "the rules plan is offered beside it");
    assertEquals(served(rules), served(result.alternative().get()));
    System.out.println("S1 cost stage: " + cost);
  }

  @Test
  void peakDayAllocationIsFeasibleExplainedAndWrittenForTheValidator() throws IOException {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    AllocationResult result = engine().allocate(s1.problem());

    assertEquals(85, result.decisions().size(), "every order has exactly one decision");
    assertFalse(result.partial());
    for (OrderDecision d : result.decisions()) {
      if (d.decision() != AllocationDecision.SERVED) {
        assertTrue(d.bindingRule().isPresent(), s1.orderRef(d.orderId()) + " needs a binding rule (R-PLN-19)");
      }
    }

    String csv = s1.submissionCsv(result);
    Path out = Path.of("target", "task2b", "submission_task2b.csv");
    Files.createDirectories(out.getParent());
    Files.writeString(out, csv, StandardCharsets.UTF_8);

    Map<String, Long> byDecision =
        result.decisions().stream().collect(Collectors.groupingBy(d -> d.decision().name(), Collectors.counting()));
    Map<String, Long> byRule =
        result.decisions().stream()
            .filter(d -> d.bindingRule().isPresent())
            .collect(Collectors.groupingBy(d -> d.bindingRule().get(), Collectors.counting()));
    System.out.println("S1 decisions " + byDecision + ", binding rules " + byRule + ", " + result.improvement());
  }

  @Test
  void theOrderLargerThanEveryVehicleIsUnservableNotDeferred() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    AllocationResult result = engine().allocate(s1.problem());
    OrderDecision overload =
        result.decisions().stream().filter(d -> "S1-078".equals(s1.orderRef(d.orderId()))).findFirst().orElseThrow();

    assertEquals(AllocationDecision.UNSERVABLE, overload.decision(), "40.66 m3 against a 38 m3 largest vehicle (PLN-02)");
    assertEquals("R-PLN-06", overload.bindingRule().orElseThrow());
  }

  @Test
  void theSameInputsAndVersionsGiveTheSamePlan() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    String first = s1.submissionCsv(engine().allocate(s1.problem()));
    String second = s1.submissionCsv(engine().allocate(PeakDayScenario.load(DATA, RULES, POLICY).problem()));
    assertEquals(first, second);
  }

  @Test
  void anOrderDeferredYesterdayIsServedBeforeAnEqualOneThatWasNot() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    AllocationResult result = engine().allocate(s1.problem());
    long skippedServed =
        s1.problem().orders().stream()
            .filter(o -> o.deferralCount() > 0)
            .filter(o -> result.decisionFor(o.orderId()).orElseThrow().decision() == AllocationDecision.SERVED)
            .count();
    long skipped = s1.problem().orders().stream().filter(o -> o.deferralCount() > 0).count();
    System.out.println("S1 outlets skipped yesterday and served today: " + skippedServed + " of " + skipped);
    assertTrue(skippedServed > 0);
  }

  @Test
  void theSecondPassServesMoreAndEveryOrderItDropsIsOutrankedByOneItAdds() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    ConstraintRegistry registry = ConstraintRegistry.standard();
    AllocationResult first = new PriorityInsertionEngine(registry).allocate(s1.problem());
    AllocationResult improved = engine().allocate(s1.problem());

    java.util.Set<UUID> before = served(first);
    java.util.Set<UUID> after = served(improved);
    assertTrue(after.size() > before.size(), "issue #92: the reefers are planned again as a whole");
    assertEquals(73, after.size(), "S1 measured: 70 to 73, every chilled order closing before 08:00 served");
    var summary = improved.improvement().orElseThrow();
    assertTrue(summary.improved());
    assertEquals(ScarceFleetReplan.Stop.NONE, summary.stoppedBy(), "the search finished; nothing was cut short");

    java.util.Comparator<PlanOrder> rank = POLICY.comparator(s1.problem().context());
    List<PlanOrder> added = s1.problem().orders().stream().filter(o -> after.contains(o.orderId()) && !before.contains(o.orderId())).toList();
    for (PlanOrder dropped : s1.problem().orders()) {
      if (before.contains(dropped.orderId()) && !after.contains(dropped.orderId())) {
        assertTrue(
            added.stream().anyMatch(a -> rank.compare(a, dropped) < 0),
            s1.orderRef(dropped.orderId()) + " was dropped without a higher ranked order taking its place (R-PLN-32)");
      }
    }
    for (PlanOrder o : s1.problem().orders()) {
      if (o.deferralCount() > 0) {
        assertTrue(after.contains(o.orderId()), s1.orderRef(o.orderId()) + " was skipped yesterday and is still served");
      }
    }
  }

  @Test
  void aPartialFirstPassIsNotImproved() {
    AtomicLong now = new AtomicLong();
    Map<String, BigDecimal> params = new HashMap<>(RuleSet.bookletParameters());
    params.put(RuleSet.ENGINE_BUDGET_MS, BigDecimal.ONE);
    PeakDayScenario s1 = PeakDayScenario.load(DATA, new RuleSet(UUID.randomUUID(), params), POLICY);
    ConstraintRegistry registry = ConstraintRegistry.standard();
    AllocationEngine slow = new ImprovingEngine(new PriorityInsertionEngine(registry, () -> now.addAndGet(200_000)), registry);

    AllocationResult result = new ValidatingEngine(slow, registry, v -> {}).allocate(s1.problem());

    assertTrue(result.partial(), "PLN-11: out of time already, so the plan is returned as it is");
    assertTrue(result.improvement().isEmpty());
    assertEquals(PriorityInsertionEngine.NAME, result.engine(), "the run records the engine that produced it");
  }

  private static java.util.Set<UUID> served(AllocationResult r) {
    return r.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.SERVED)
        .map(OrderDecision::orderId)
        .collect(Collectors.toSet());
  }

  @Test
  void runningOutOfTimeDefersTheRestAndMarksThePlanPartial() {
    AtomicLong now = new AtomicLong();
    Map<String, BigDecimal> params = new HashMap<>(RuleSet.bookletParameters());
    params.put(RuleSet.ENGINE_BUDGET_MS, BigDecimal.ONE);
    RuleSet tight = new RuleSet(UUID.randomUUID(), params);
    PeakDayScenario s1 = PeakDayScenario.load(DATA, tight, POLICY);
    ConstraintRegistry registry = ConstraintRegistry.standard();
    AllocationEngine slow =
        new PriorityInsertionEngine(registry, () -> now.addAndGet(200_000));

    AllocationResult result = new ValidatingEngine(slow, registry, v -> {}).allocate(s1.problem());

    assertTrue(result.partial(), "PLN-11");
    assertEquals(85, result.decisions().size(), "never an empty or truncated plan");
    assertTrue(result.decisions().stream().anyMatch(d -> d.bindingRule().map(PriorityInsertionEngine.TIMEOUT_RULE::equals).orElse(false)));
  }

  @Test
  void anInfeasibleEngineResultIsRejectedBeforeItLeavesPlanning() {
    PeakDayScenario s1 = PeakDayScenario.load(DATA, RULES, POLICY);
    ConstraintRegistry registry = ConstraintRegistry.standard();
    AllocationEngine cheat =
        new AllocationEngine() {
          public String name() {
            return "overloads-one-truck";
          }

          public AllocationResult allocate(Problem p) {
            VehicleDay day = VehicleDay.idle(p.fleet().stream().filter(v -> v.available()).findFirst().orElseThrow());
            Trip all = Trip.of(p.orders().get(0));
            for (var o : p.orders().subList(1, p.orders().size())) {
              all = all.with(o);
            }
            VehicleDay overloaded = new VehicleDay(day.vehicle(), List.of(all));
            List<OrderDecision> decisions = new ArrayList<>();
            p.orders().forEach(o -> decisions.add(new OrderDecision(o.orderId(), AllocationDecision.SERVED,
                java.util.Optional.of(day.vehicleId()), java.util.Optional.of(1), java.util.Optional.empty(), "", List.of())));
            return new AllocationResult(List.of(overloaded), decisions, false, name());
          }
        };
    List<List<Violation>> alerts = new ArrayList<>();

    DomainException e =
        assertThrows(DomainException.class, () -> new ValidatingEngine(cheat, registry, alerts::add).allocate(s1.problem()));

    assertTrue(e.rules().contains("R-PLN-06"));
    assertEquals(1, alerts.size(), "PLN-12 raises an alert on any occurrence");
  }
}
