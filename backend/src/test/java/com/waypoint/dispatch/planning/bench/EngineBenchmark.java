package com.waypoint.dispatch.planning.bench;

import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.infrastructure.ImprovingEngine;
import com.waypoint.dispatch.planning.infrastructure.PriorityInsertionEngine;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Every engine on the same problems, judged by the same production checker.
 *
 * <pre>mvn test -Dtest=EngineBenchmark -Dbench=s1|historic|synthetic|all [-Dbench.seeds=5] [-Dbench.engines=E0,E1,...]</pre>
 *
 * Skipped unless {@code -Dbench} is given, so {@code mvn verify} stays fast.
 */
class EngineBenchmark {
  static final long BUDGET_MS = 10_000;

  static Map<String, Supplier<AllocationEngine>> engines() {
    Map<String, Supplier<AllocationEngine>> m = new LinkedHashMap<>();
    m.put("E0", () -> new PriorityInsertionEngine(Bench.REGISTRY));
    m.put("E1", () -> new ImprovingEngine(new PriorityInsertionEngine(Bench.REGISTRY), Bench.REGISTRY));
    m.put("E2-cpsat", () -> new MipEngine("CP_SAT", BUDGET_MS, false));
    m.put("E2-highs", () -> new MipEngine("HIGHS", BUDGET_MS, false));
    m.put("E2-scip", () -> new MipEngine("SCIP", BUDGET_MS, false));
    // The same model with window rows and time to prove: the reference.
    m.put("E2-ref", () -> new MipEngine("CP_SAT", Long.getLong("bench.refMs", 300_000L), true));
    m.put("E3-alns", () -> new AlnsEngine(42, BUDGET_MS));
    m.put("E4-routing", () -> new RoutingEngine(BUDGET_MS));
    // What production runs (PlanningEngineConfiguration): rules, exact reefer pass, then the cost stage with its own budget.
    m.put("E5-production", () -> new com.waypoint.dispatch.planning.infrastructure.ValidatingEngine(
        new com.waypoint.dispatch.planning.infrastructure.CostImprovingEngine(
            new ImprovingEngine(new PriorityInsertionEngine(Bench.REGISTRY), Bench.REGISTRY), Bench.REGISTRY),
        Bench.REGISTRY, v -> {}));
    return m;
  }

  record Row(String scenario, String family, int orders, String engine, Bench.Measure m, long ms, int rankVsBest,
      String extra) {}

  @Test
  void run() throws IOException {
    String which = System.getProperty("bench");
    Assumptions.assumeTrue(which != null, "pass -Dbench=s1|historic|synthetic|all");
    int seeds = Integer.getInteger("bench.seeds", 5);
    String only = System.getProperty("bench.engines", "");

    List<Scenarios.Scenario> scenarios = new ArrayList<>();
    Scenarios.Reference ref = new Scenarios.Reference();
    if (which.equals("s1") || which.equals("all")) {
      scenarios.add(Scenarios.s1());
    }
    if (which.equals("historic") || which.equals("all")) {
      scenarios.addAll(Scenarios.historic(ref));
    }
    if (which.equals("synthetic") || which.equals("all")) {
      for (Scenarios.Family f : Scenarios.FAMILIES) {
        int n = f.orders() >= 300 ? Math.max(1, seeds / 2) : seeds;
        for (long s = 1; s <= n; s++) {
          scenarios.add(Scenarios.synthetic(ref, f, s));
        }
      }
    }

    String onlyScenario = System.getProperty("bench.scenario", "");
    if (!onlyScenario.isBlank()) {
      scenarios.removeIf(sc -> !sc.name().equals(onlyScenario));
    }
    Path out = Path.of("target", "bench");
    Files.createDirectories(out);
    StringBuilder csv = new StringBuilder(
        "scenario,family,orders,engine,valid,served,deferred,unservable,chilled_served,prior_skip_served,vehicles,trips,litres,km,ms,rank_vs_best,fingerprint,extra,violation\n");
    List<Row> rows = new ArrayList<>();
    for (Scenarios.Scenario sc : scenarios) {
      Map<String, AllocationResult> results = new LinkedHashMap<>();
      Map<String, Long> times = new LinkedHashMap<>();
      Map<String, String> extras = new LinkedHashMap<>();
      for (Map.Entry<String, Supplier<AllocationEngine>> e : engines().entrySet()) {
        if (!only.isBlank() && !List.of(only.split(",")).contains(e.getKey())) {
          continue;
        }
        // HiGHS through OR-Tools 9.14 ignores its time limit on these models (one ran 1.5 h): S1 only.
        if (e.getKey().equals("E2-highs") && !sc.name().equals("S1")) {
          continue;
        }
        AllocationEngine engine = e.getValue().get();
        long t0 = System.nanoTime();
        AllocationResult r;
        try {
          r = engine.allocate(sc.problem());
        } catch (RuntimeException ex) {
          System.out.println(sc.name() + " " + e.getKey() + " FAILED: " + ex);
          continue;
        }
        times.put(e.getKey(), (System.nanoTime() - t0) / 1_000_000L);
        results.put(e.getKey(), r);
        String repeat = "";
        if (Boolean.getBoolean("bench.repeat")) {
          AllocationResult again = e.getValue().get().allocate(sc.problem());
          repeat = Bench.fingerprint(again).equals(Bench.fingerprint(r)) ? " repeatable" : " NOT-repeatable";
        }
        final String rep = repeat;
        if (engine instanceof MipEngine mip) {
          extras.put(e.getKey(), "solves=" + mip.solves + " cuts=" + mip.cuts + " unproven=" + mip.unproven
              + (mip.costDone ? " cost=optimal" : " cost=unfinished")
              + String.format(" obj=%.1f bound=%.1f", mip.lastCost, mip.lastBound) + (mip.stoppedByBudget ? " budget" : "") + rep);
        } else if (engine instanceof Extra x) {
          extras.put(e.getKey(), x.extra() + rep);
        } else if (r.cost().isPresent()) {
          var c = r.cost().get();
          extras.put(e.getKey(), ("cost=" + c.trigger() + " vehicles " + c.rulesVehicles() + "->" + c.vehicles()
              + " litres " + c.rulesLitres() + "->" + c.litres() + " iterations=" + c.iterations() + " " + c.stoppedBy() + rep).trim());
        } else {
          extras.put(e.getKey(), rep.trim());
        }
      }
      // The best plan by rank among the valid ones; each engine is compared with it.
      Set<UUID> unservable = new java.util.HashSet<>();
      results.values().stream().findFirst().ifPresent(r -> r.decisions().stream()
          .filter(d -> d.decision() == com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision.UNSERVABLE)
          .forEach(d -> unservable.add(d.orderId())));
      List<PlanOrder> ranked = Bench.ranked(sc.problem(), unservable);
      Map<String, Bench.Measure> measures = new LinkedHashMap<>();
      Set<UUID> best = null;
      for (Map.Entry<String, AllocationResult> e : results.entrySet()) {
        Bench.Measure m = Bench.measure(sc.problem(), e.getValue());
        measures.put(e.getKey(), m);
        Set<UUID> served = Bench.servedIds(e.getValue());
        if (m.violations() == 0 && (best == null || Bench.compareByRank(ranked, served, best) > 0)) {
          best = served;
        }
      }
      for (Map.Entry<String, AllocationResult> e : results.entrySet()) {
        Bench.Measure m = measures.get(e.getKey());
        int vsBest = best == null ? 0 : Bench.compareByRank(ranked, Bench.servedIds(e.getValue()), best);
        Row row = new Row(sc.name(), sc.family(), sc.problem().orders().size(), e.getKey(), m, times.get(e.getKey()),
            vsBest, extras.getOrDefault(e.getKey(), ""));
        rows.add(row);
        csv.append(String.join(",", sc.name(), sc.family(), String.valueOf(row.orders()), e.getKey(),
            String.valueOf(m.violations() == 0), String.valueOf(m.served()), String.valueOf(m.deferred()),
            String.valueOf(m.unservable()), String.valueOf(m.chilledServed()), String.valueOf(m.priorSkipServed()),
            String.valueOf(m.vehicles()), String.valueOf(m.trips()), m.litres().toPlainString(), m.km().toPlainString(),
            String.valueOf(row.ms()), String.valueOf(vsBest), m.fingerprint(), row.extra().replace(',', ';'),
            m.firstViolation().replace(',', ';'))).append('\n');
        System.out.printf("%-34s %-9s valid=%-5s served=%3d chilled=%3d veh=%2d trips=%2d litres=%8s ms=%6d vsBest=%2d %s %s%n",
            sc.name(), e.getKey(), m.violations() == 0, m.served(), m.chilledServed(), m.vehicles(), m.trips(),
            m.litres().setScale(1, java.math.RoundingMode.HALF_UP), row.ms(), vsBest, row.extra(), m.firstViolation());
      }
      Files.writeString(out.resolve("results-" + which + ".csv"), csv.toString());
    }
    Files.writeString(out.resolve("summary-" + which + ".md"), summary(rows));
    System.out.println(summary(rows));
  }

  /** Per engine: scenarios where it is best or tied best by rank, served, vehicles, litres, time. */
  static String summary(List<Row> rows) {
    Map<String, List<Row>> by = new LinkedHashMap<>();
    rows.forEach(r -> by.computeIfAbsent(r.engine(), k -> new ArrayList<>()).add(r));
    StringBuilder b = new StringBuilder(
        "| Engine | Scenarios | Invalid | Best by rank | Behind best | Served | Vehicles | Litres | Mean ms | Max ms |\n"
            + "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (Map.Entry<String, List<Row>> e : by.entrySet()) {
      List<Row> rs = e.getValue();
      long invalid = rs.stream().filter(r -> r.m().violations() > 0).count();
      long best = rs.stream().filter(r -> r.m().violations() == 0 && r.rankVsBest() == 0).count();
      long behind = rs.stream().filter(r -> r.rankVsBest() < 0).count();
      int served = rs.stream().mapToInt(r -> r.m().served()).sum();
      int vehicles = rs.stream().mapToInt(r -> r.m().vehicles()).sum();
      BigDecimal litres = rs.stream().map(r -> r.m().litres()).reduce(BigDecimal.ZERO, BigDecimal::add);
      long mean = (long) rs.stream().mapToLong(Row::ms).average().orElse(0);
      long max = rs.stream().mapToLong(Row::ms).max().orElse(0);
      b.append(String.format("| %s | %d | %d | %d | %d | %d | %d | %s | %d | %d |%n", e.getKey(), rs.size(), invalid, best,
          behind, served, vehicles, litres.setScale(0, java.math.RoundingMode.HALF_UP), mean, max));
    }
    return b.toString();
  }

  /** An engine that has something of its own to report. */
  interface Extra {
    String extra();
  }
}
