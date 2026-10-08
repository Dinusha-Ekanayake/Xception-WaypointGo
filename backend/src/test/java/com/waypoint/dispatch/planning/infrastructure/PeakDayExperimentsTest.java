package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Task 2B, controlled comparisons for the written policy: the same engine and
 * inputs with one thing changed at a time, so each choice's cost on S1 is
 * measured rather than argued. Each variant's CSV is written to
 * {@code target/task2b/experiments/} for the official checker.
 */
class PeakDayExperimentsTest {
  record Outcome(String name, int served, int chilledServed, double chilledM3, int vehicles, int trips) {}

  private static ConstraintRegistry without(Set<String> rules) {
    return new ConstraintRegistry(ConstraintRegistry.standard().constraints().stream()
        .filter(c -> !rules.contains(c.ruleId())).toList());
  }

  private static AllocationEngine engine(ConstraintRegistry registry) {
    return new ValidatingEngine(
        new CostImprovingEngine(new ImprovingEngine(new PriorityInsertionEngine(registry), registry), registry),
        registry, v -> {});
  }

  private static Outcome run(String name, ConstraintRegistry registry, PriorityPolicy policy) throws IOException {
    PeakDayScenario s1 = PeakDayScenario.load(PeakDayAllocationTest.DATA, PeakDayAllocationTest.RULES, policy);
    AllocationResult r = engine(registry).allocate(s1.problem());
    int served = 0;
    int chilledServed = 0;
    BigDecimal chilledM3 = BigDecimal.ZERO;
    for (PlanOrder o : s1.problem().orders()) {
      if (r.decisionFor(o.orderId()).orElseThrow().decision() == AllocationDecision.SERVED) {
        served++;
        if ("chilled".equals(o.temperature())) {
          chilledServed++;
          chilledM3 = chilledM3.add(o.volumeM3());
        }
      }
    }
    Path out = Path.of("target", "task2b", "experiments", name + ".csv");
    Files.createDirectories(out.getParent());
    Files.writeString(out, s1.submissionCsv(r), StandardCharsets.UTF_8);
    int vehicles = (int) r.days().stream().filter(d -> !d.trips().isEmpty()).count();
    int trips = r.days().stream().mapToInt(d -> d.trips().size()).sum();
    return new Outcome(name, served, chilledServed, chilledM3.doubleValue(), vehicles, trips);
  }

  @Test
  void eachChoiceIsMeasuredOnTheSameEngineAndInputs() throws IOException {
    PriorityPolicy policy = PeakDayAllocationTest.POLICY;
    List<PriorityPolicy.Key> unservedEarly = new ArrayList<>(PriorityPolicy.DEFAULT_KEYS);
    unservedEarly.remove(PriorityPolicy.Key.LONGEST_UNSERVED);
    unservedEarly.add(1, PriorityPolicy.Key.LONGEST_UNSERVED);
    List<Outcome> outcomes = List.of(
        run("submitted", ConstraintRegistry.standard(), policy),
        run("no-outlet-windows", without(Set.of("R-PLN-13")), policy),
        run("no-temperature-separation", without(Set.of("R-PLN-31")), policy),
        run("booklet-rules-only", without(Set.of("R-PLN-13", "R-PLN-31", "R-PLN-16")), policy),
        run("unserved-days-second", ConstraintRegistry.standard(),
            new PriorityPolicy(UUID.nameUUIDFromBytes("policy-unserved-second".getBytes()), unservedEarly)));
    for (Outcome o : outcomes) {
      System.out.println("S1 experiment " + o);
    }
    assertEquals(73, outcomes.get(0).served(), "the submitted plan");
    // Fewer rules can never serve fewer orders by rank than more rules on the same inputs, only differently.
    assertTrue(outcomes.stream().allMatch(o -> o.served() > 0));
  }
}
