package com.waypoint.dispatch.planning.bench;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.CheapestInsertion;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.FuelLedger;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanVerification;
import com.waypoint.dispatch.planning.domain.TemperatureClass;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** What every engine in the benchmark shares: one registry, one way to decide orders, one way to measure. */
final class Bench {
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();
  static final CheapestInsertion INSERTION = new CheapestInsertion(REGISTRY);

  private Bench() {}

  static boolean feasible(VehicleDay day, PlanContext ctx) {
    return ConstraintRegistry.allPass(REGISTRY.evaluate(new Candidate(day, ctx, Set.of())));
  }

  /** Servable orders highest rank first, with the unservable ones the baseline found left out. */
  static List<PlanOrder> ranked(Problem p, Set<UUID> unservable) {
    return p.policy().rank(p.orders().stream().filter(o -> !unservable.contains(o.orderId())).toList(), p.context());
  }

  /** Every order decided against {@code days}, deferrals explained the way production explains them. */
  static AllocationResult finish(Problem p, Collection<VehicleDay> days, List<OrderDecision> unservable, String engine) {
    PlanContext ctx = p.context();
    Set<UUID> skip = unservable.stream().map(OrderDecision::orderId).collect(Collectors.toSet());
    Set<UUID> served = servedIds(days);
    List<OrderDecision> decisions = new ArrayList<>(unservable);
    for (PlanOrder o : p.orders()) {
      if (skip.contains(o.orderId())) {
        continue;
      }
      decisions.add(served.contains(o.orderId())
          ? INSERTION.served(o, days, ctx)
          : CheapestInsertion.deferred(o, INSERTION.place(days, o, ctx).closest()));
    }
    decisions.sort(Comparator.comparing(OrderDecision::orderId));
    return new AllocationResult(List.copyOf(days), decisions, false, engine);
  }

  static Set<UUID> servedIds(Collection<VehicleDay> days) {
    Set<UUID> s = new HashSet<>();
    days.forEach(d -> d.trips().forEach(t -> t.orders().forEach(o -> s.add(o.orderId()))));
    return s;
  }

  static Set<UUID> servedIds(AllocationResult r) {
    return r.decisions().stream().filter(d -> d.decision() == AllocationDecision.SERVED)
        .map(OrderDecision::orderId).collect(Collectors.toSet());
  }

  /** The highest ranked order one set serves and the other does not decides; positive when {@code a} is better. */
  static int compareByRank(List<PlanOrder> ranked, Set<UUID> a, Set<UUID> b) {
    for (PlanOrder o : ranked) {
      boolean inA = a.contains(o.orderId());
      if (inA != b.contains(o.orderId())) {
        return inA ? 1 : -1;
      }
    }
    return 0;
  }

  /** What the table reports for one engine on one scenario. */
  record Measure(int served, int deferred, int unservable, int chilledServed, int priorSkipServed, int vehicles,
      int trips, BigDecimal litres, BigDecimal km, int violations, String firstViolation, String fingerprint) {}

  static Measure measure(Problem p, AllocationResult r) {
    PlanContext ctx = p.context();
    Set<UUID> demand = p.orders().stream().map(PlanOrder::orderId).collect(Collectors.toSet());
    List<PlanVerification.Violation> v = PlanVerification.verify(r, demand, ctx, REGISTRY);
    Set<UUID> served = servedIds(r);
    int unservable = (int) r.decisions().stream().filter(d -> d.decision() == AllocationDecision.UNSERVABLE).count();
    int chilled = (int) p.orders().stream()
        .filter(o -> served.contains(o.orderId()) && o.temperatureClass() == TemperatureClass.CHILLED).count();
    int skips = (int) p.orders().stream().filter(o -> served.contains(o.orderId()) && o.deferralCount() > 0).count();
    int vehicles = 0;
    int trips = 0;
    BigDecimal litres = BigDecimal.ZERO;
    BigDecimal km = BigDecimal.ZERO;
    for (VehicleDay d : r.days()) {
      if (d.trips().isEmpty()) {
        continue;
      }
      vehicles++;
      trips += d.trips().size();
      litres = litres.add(FuelLedger.dayLitres(d, p.travel()));
      for (Trip t : d.trips()) {
        km = km.add(FuelLedger.tripKm(t, p.travel().get(t.district())));
      }
    }
    return new Measure(served.size(), r.decisions().size() - served.size() - unservable, unservable, chilled, skips,
        vehicles, trips, litres, km, v.size(), v.isEmpty() ? "" : v.get(0).ruleId() + " " + v.get(0).reason(),
        fingerprint(r));
  }

  /** The plan as text, for checking that two runs made the same plan. */
  static String fingerprint(AllocationResult r) {
    TreeMap<String, String> m = new TreeMap<>();
    for (VehicleDay d : r.days()) {
      StringBuilder b = new StringBuilder();
      for (Trip t : d.trips()) {
        b.append(t.orders().stream().map(PlanOrder::orderRef).sorted().collect(Collectors.joining(","))).append('|');
      }
      if (!d.trips().isEmpty()) {
        m.put(d.vehicleId(), b.toString());
      }
    }
    return Integer.toHexString(m.toString().hashCode());
  }
}
