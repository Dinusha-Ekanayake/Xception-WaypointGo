package com.waypoint.dispatch.planning.bench;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.FuelLedger;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.TemperatureClass;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import com.waypoint.dispatch.planning.infrastructure.ImprovingEngine;
import com.waypoint.dispatch.planning.infrastructure.PriorityInsertionEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * E3: adaptive large neighbourhood search from today's plan. Each iteration
 * removes some orders (at random, a whole vehicle, a district's orders, or the
 * emptiest trip) and puts back everything unserved in rank order, preferring
 * vehicles already in use, every placement checked by the production
 * registry. A plan is better when it serves a better set by rank (R-PLN-21),
 * then uses fewer vehicles, then fewer litres. Seeded and stopped on an
 * iteration count, so a run is repeatable; the clock is a safety stop.
 */
final class AlnsEngine implements AllocationEngine, EngineBenchmark.Extra {
  private static final String[] OPS = {"random", "vehicle", "related", "trip"};

  private final long seed;
  private final long budgetMs;
  private final int maxIterations;
  private int iterations;
  private int improvements;
  private boolean clockStop;

  AlnsEngine(long seed, long budgetMs) {
    this(seed, budgetMs, Integer.getInteger("bench.alnsIterations", 2000));
  }

  AlnsEngine(long seed, long budgetMs, int maxIterations) {
    this.seed = seed;
    this.budgetMs = budgetMs;
    this.maxIterations = maxIterations;
  }

  @Override
  public String name() {
    return "E3-alns";
  }

  @Override
  public String extra() {
    return "iterations=" + iterations + " improvements=" + improvements + (clockStop ? " clock" : "");
  }

  private record Score(Set<UUID> served, int vehicles, double litres) {}

  @Override
  public AllocationResult allocate(Problem p) {
    long deadline = System.nanoTime() + budgetMs * 1_000_000L;
    Random random = new Random(seed);
    PlanContext ctx = p.context();
    AllocationResult start = new ImprovingEngine(new PriorityInsertionEngine(Bench.REGISTRY), Bench.REGISTRY).allocate(p);
    List<OrderDecision> unservable = start.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.UNSERVABLE).toList();
    Set<UUID> skip = new HashSet<>();
    unservable.forEach(d -> skip.add(d.orderId()));
    List<PlanOrder> ranked = Bench.ranked(p, skip);
    Map<UUID, Integer> rank = new HashMap<>();
    for (int i = 0; i < ranked.size(); i++) {
      rank.put(ranked.get(i).orderId(), i);
    }

    Map<String, VehicleDay> current = new TreeMap<>();
    p.fleet().stream().filter(FleetVehicle::available).forEach(v -> current.put(v.vehicleId(), VehicleDay.idle(v)));
    start.days().forEach(d -> current.put(d.vehicleId(), d));
    Map<String, VehicleDay> best = new TreeMap<>(current);
    Score currentScore = score(current, p);
    Score bestScore = currentScore;

    double[] weights = {1, 1, 1, 1};
    double temperature = 30;
    iterations = 0;
    improvements = 0;
    clockStop = false;
    for (int it = 0; it < maxIterations; it++) {
      if (System.nanoTime() > deadline) {
        clockStop = true;
        break;
      }
      iterations++;
      int op = pick(weights, random);
      Map<String, VehicleDay> next = new TreeMap<>(current);
      destroy(OPS[op], next, random, p);
      repair(next, ranked, rank, random, ctx);
      Score s = score(next, p);
      int vsCurrent = compare(ranked, s, currentScore);
      int vsBest = compare(ranked, s, bestScore);
      boolean accept = vsCurrent > 0
          || (sameServed(s, currentScore) && random.nextDouble() < Math.exp(-(scalar(s) - scalar(currentScore)) / temperature));
      if (vsBest > 0) {
        best = new TreeMap<>(next);
        bestScore = s;
        improvements++;
        weights[op] += 3;
      } else if (accept && vsCurrent > 0) {
        weights[op] += 1;
      }
      if (accept) {
        current.clear();
        current.putAll(next);
        currentScore = s;
      }
      temperature *= 0.997;
    }
    return Bench.finish(p, best.values(), unservable, name());
  }

  private static int pick(double[] weights, Random random) {
    double total = 0;
    for (double w : weights) {
      total += w;
    }
    double r = random.nextDouble() * total;
    for (int i = 0; i < weights.length; i++) {
      r -= weights[i];
      if (r <= 0) {
        return i;
      }
    }
    return weights.length - 1;
  }

  private static void destroy(String op, Map<String, VehicleDay> days, Random random, Problem p) {
    List<VehicleDay> used = days.values().stream().filter(d -> !d.trips().isEmpty()).toList();
    if (used.isEmpty()) {
      return;
    }
    switch (op) {
      case "vehicle" -> {
        // Empty one vehicle, the lighter ones more often: the way to a plan with fewer vehicles.
        VehicleDay victim = used.stream()
            .min(java.util.Comparator.comparingDouble((VehicleDay d) -> load(d) * (0.5 + random.nextDouble())))
            .orElseThrow();
        days.put(victim.vehicleId(), VehicleDay.idle(victim.vehicle()));
      }
      case "trip" -> {
        VehicleDay victim = used.get(random.nextInt(used.size()));
        Trip smallest = victim.trips().stream().min(java.util.Comparator.comparing(Trip::volumeM3)).orElseThrow();
        VehicleDay rest = victim;
        for (PlanOrder o : smallest.orders()) {
          rest = rest.without(o.orderId());
        }
        days.put(victim.vehicleId(), rest);
      }
      case "related" -> {
        VehicleDay from = used.get(random.nextInt(used.size()));
        Trip t = from.trips().get(random.nextInt(from.trips().size()));
        String district = t.district();
        for (VehicleDay d : List.copyOf(days.values())) {
          VehicleDay rest = d;
          for (Trip trip : d.trips()) {
            if (trip.district().equals(district) && random.nextDouble() < 0.6) {
              for (PlanOrder o : trip.orders()) {
                rest = rest.without(o.orderId());
              }
            }
          }
          days.put(d.vehicleId(), rest);
        }
      }
      default -> {
        int q = 3 + random.nextInt(10);
        for (int i = 0; i < q; i++) {
          List<VehicleDay> nonEmpty = days.values().stream().filter(d -> !d.trips().isEmpty()).toList();
          if (nonEmpty.isEmpty()) {
            return;
          }
          VehicleDay d = nonEmpty.get(random.nextInt(nonEmpty.size()));
          List<PlanOrder> orders = d.trips().stream().flatMap(t -> t.orders().stream()).toList();
          days.put(d.vehicleId(), d.without(orders.get(random.nextInt(orders.size())).orderId()));
        }
      }
    }
  }

  private static double load(VehicleDay d) {
    return d.trips().stream().mapToDouble(t -> t.volumeM3().doubleValue()).sum() / d.vehicle().volumeCapM3().doubleValue();
  }

  /**
   * Every unserved order back in rank order (neighbours swapped now and then),
   * each to the place that adds least: an open trip, then a new trip on a
   * vehicle already out, then a vehicle not yet used; fewest added litres within that.
   */
  private static void repair(Map<String, VehicleDay> days, List<PlanOrder> ranked, Map<UUID, Integer> rank,
      Random random, PlanContext ctx) {
    Set<UUID> served = Bench.servedIds(days.values());
    List<PlanOrder> todo = new ArrayList<>(ranked.stream().filter(o -> !served.contains(o.orderId())).toList());
    for (int i = 0; i + 1 < todo.size(); i++) {
      if (random.nextDouble() < 0.15) {
        java.util.Collections.swap(todo, i, i + 1);
      }
    }
    for (PlanOrder o : todo) {
      VehicleDay bestDay = null;
      double bestCost = Double.MAX_VALUE;
      for (VehicleDay d : days.values()) {
        FleetVehicle v = d.vehicle();
        if ((o.temperatureClass() == TemperatureClass.CHILLED && !v.reefer()) || (o.vanOnly() && !v.van())) {
          continue;
        }
        double before = d.trips().isEmpty() ? 0 : FuelLedger.dayLitres(d, ctx.travel()).doubleValue();
        List<VehicleDay> options = new ArrayList<>();
        for (int n = 1; n <= d.trips().size(); n++) {
          if (d.trip(n).accepts(o)) {
            options.add(d.withJoined(n, o));
          }
        }
        if (d.trips().size() < ctx.rules().maxTrips()) {
          options.add(d.withNewTrip(o));
        }
        for (VehicleDay option : options) {
          double tier = d.trips().isEmpty() ? 2000 : option.trips().size() > d.trips().size() ? 1000 : 0;
          // Keep reefers and vans for what only they can carry.
          double scarce = (v.reefer() && o.temperatureClass() != TemperatureClass.CHILLED ? 300 : 0)
              + (v.van() && !o.vanOnly() ? 150 : 0);
          double added = FuelLedger.dayLitres(option, ctx.travel()).doubleValue() - before;
          double cost = tier + scarce + added;
          if (cost < bestCost && Bench.feasible(option, ctx)) {
            bestCost = cost;
            bestDay = option;
          }
        }
      }
      if (bestDay != null) {
        days.put(bestDay.vehicleId(), bestDay);
      }
    }
  }

  private static Score score(Map<String, VehicleDay> days, Problem p) {
    int vehicles = 0;
    double litres = 0;
    for (VehicleDay d : days.values()) {
      if (!d.trips().isEmpty()) {
        vehicles++;
        litres += FuelLedger.dayLitres(d, p.travel()).doubleValue();
      }
    }
    return new Score(Bench.servedIds(days.values()), vehicles, litres);
  }

  private static int compare(List<PlanOrder> ranked, Score a, Score b) {
    int byRank = Bench.compareByRank(ranked, a.served(), b.served());
    if (byRank != 0) {
      return byRank;
    }
    if (a.vehicles() != b.vehicles()) {
      return a.vehicles() < b.vehicles() ? 1 : -1;
    }
    return Double.compare(b.litres(), a.litres()) > 0 ? 1 : Double.compare(b.litres(), a.litres()) < 0 ? -1 : 0;
  }

  private static boolean sameServed(Score a, Score b) {
    return a.served().equals(b.served());
  }

  private static double scalar(Score s) {
    return 1000.0 * s.vehicles() + s.litres();
  }
}
