package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The cost stage (planning v2): the same orders on fewer vehicles and less
 * fuel. Adaptive large neighbourhood search from the plan the rules made.
 *
 * <p>Each iteration takes some orders out (at random, a whole vehicle, part of a
 * district, or the emptiest trip) and puts every unserved order back in rank
 * order, preferring vehicles already out and the fewest added litres, every
 * placement checked by the {@link ConstraintRegistry}. A plan is better when it
 * serves a better set by rank (R-PLN-21), then uses fewer vehicles, then fewer
 * litres, so the stage can never defer an order the rules served (R-PLN-38).
 *
 * <p>It runs only on a day worth it (R-PLN-39): the rules plan deferred
 * something, or its trips are on average less full than
 * {@link #MIN_UTILISATION}. A simple day keeps the rules plan.
 *
 * <p>Pure and repeatable: the random stream is seeded from the orders, the
 * search stops on an iteration count that is part of the engine version, and
 * the clock is a safety stop only (as R-PLN-32).
 */
public final class CostReplan {
  public static final String NAME = "cost-alns-v1";

  /** The search length of this engine version. Changing it is a new engine name. */
  public static final int ITERATIONS = 2000;

  /** Below this average trip fullness a day is worth searching even with nothing deferred. */
  public static final BigDecimal MIN_UTILISATION = new BigDecimal("0.85");

  public static final String ITERATIONS_KEY = "engine.cost.iterations";
  public static final String MIN_UTILISATION_KEY = "engine.cost.min.utilisation";
  public static final String ENABLED_KEY = "engine.cost.enabled";
  public static final String BUDGET_KEY = "engine.cost.budget.ms";

  /** The cost stage's own time budget, counted from when the rules plan is ready; a safety stop, not the search length. */
  public static final long BUDGET_MS = 5_000;

  public static long budgetMillis(RuleSet rules) {
    return rules.optional(BUDGET_KEY).map(BigDecimal::longValue).orElse(BUDGET_MS);
  }

  public enum Trigger {
    DEFERRALS,
    LOW_UTILISATION,
    SKIPPED_SIMPLE_DAY,
    SKIPPED_KEPT_DECISIONS,
    SKIPPED_DISABLED
  }

  public enum Stop {
    NONE,
    CLOCK
  }

  /**
   * @param improved whether the stage replaced the rules plan
   * @param iterations how many it ran; zero when skipped
   */
  public record Summary(
      Trigger trigger,
      boolean improved,
      int rulesVehicles,
      int rulesTrips,
      BigDecimal rulesLitres,
      int vehicles,
      int trips,
      BigDecimal litres,
      int iterations,
      Stop stoppedBy) {}

  public record Result(AllocationResult allocation, Summary summary) {}

  private static final String[] OPERATORS = {"random", "vehicle", "related", "trip"};

  private final ConstraintRegistry registry;
  private final CheapestInsertion insertion;
  private final int defaultIterations;

  public CostReplan(ConstraintRegistry registry) {
    this(registry, ITERATIONS);
  }

  CostReplan(ConstraintRegistry registry, int iterations) {
    this.registry = registry;
    this.insertion = new CheapestInsertion(registry);
    this.defaultIterations = iterations;
  }

  /**
   * @param rules the plan the rules made; returned unchanged unless a strictly better one is found
   * @param nanoTime the clock, read only against {@code deadlineNanos}
   */
  public Result improve(Problem problem, AllocationResult rules, LongSupplier nanoTime, long deadlineNanos, String engine) {
    PlanContext ctx = problem.context();
    Measure start = measure(rules.days(), problem);
    Trigger trigger = trigger(problem, rules);
    if (trigger.name().startsWith("SKIPPED")) {
      return new Result(rules, summary(trigger, false, start, start, 0, Stop.NONE));
    }

    Set<UUID> unservable = new HashSet<>();
    rules.decisions().stream().filter(d -> d.decision() == AllocationDecision.UNSERVABLE)
        .forEach(d -> unservable.add(d.orderId()));
    List<PlanOrder> ranked =
        problem.policy().rank(problem.orders().stream().filter(o -> !unservable.contains(o.orderId())).toList(), ctx);

    Map<String, VehicleDay> initial = new TreeMap<>();
    problem.fleet().stream().filter(FleetVehicle::available).forEach(v -> initial.put(v.vehicleId(), VehicleDay.idle(v)));
    rules.days().forEach(d -> initial.put(d.vehicleId(), d));
    Map<String, VehicleDay> current = initial;
    Map<String, VehicleDay> best = new TreeMap<>(initial);
    Measure currentMeasure = start;
    Measure bestMeasure = start;

    Random random = new Random(seed(problem));
    int iterations = iterations(problem);
    double[] weights = {1, 1, 1, 1};
    double temperature = 30;
    int ran = 0;
    Stop stop = Stop.NONE;
    for (int it = 0; it < iterations; it++) {
      if ((it & 15) == 0 && nanoTime.getAsLong() > deadlineNanos) {
        stop = Stop.CLOCK;
        break;
      }
      ran++;
      int op = pick(weights, random);
      Map<String, VehicleDay> next = new TreeMap<>(current);
      destroy(OPERATORS[op], next, random);
      repair(next, ranked, random, ctx);
      Measure m = measure(next.values(), problem);
      int vsCurrent = compare(ranked, m, currentMeasure);
      boolean accept = vsCurrent > 0
          || (m.served().equals(currentMeasure.served())
              && random.nextDouble() < Math.exp(-(m.scalar() - currentMeasure.scalar()) / temperature));
      if (compare(ranked, m, bestMeasure) > 0) {
        best = new TreeMap<>(next);
        bestMeasure = m;
        weights[op] += 3;
      } else if (vsCurrent > 0) {
        weights[op] += 1;
      }
      if (accept) {
        current = next;
        currentMeasure = m;
      }
      temperature *= 0.997;
    }

    if (compare(ranked, bestMeasure, start) <= 0) {
      return new Result(rules, summary(trigger, false, start, start, ran, stop));
    }
    // Decide every order against the new plan, deferrals explained as the rules explain them (R-PLN-19).
    List<OrderDecision> decisions = new ArrayList<>();
    for (PlanOrder o : ranked) {
      decisions.add(bestMeasure.served().contains(o.orderId())
          ? insertion.served(o, best.values(), ctx)
          : CheapestInsertion.deferred(o, insertion.place(best.values(), o, ctx).closest()));
    }
    rules.decisions().stream().filter(d -> unservable.contains(d.orderId())).forEach(decisions::add);
    decisions.sort(Comparator.comparing(OrderDecision::orderId));
    Summary summary = summary(trigger, true, start, bestMeasure, ran, stop);
    AllocationResult improved = new AllocationResult(
        List.copyOf(best.values()), decisions, false, engine, rules.improvement(), Optional.of(summary),
        Optional.of(rules));
    return new Result(improved, summary);
  }

  /** Why the stage runs, or why it does not. */
  Trigger trigger(Problem problem, AllocationResult rules) {
    Optional<BigDecimal> enabled = problem.rules().optional(ENABLED_KEY);
    if (enabled.isPresent() && enabled.get().signum() == 0) {
      return Trigger.SKIPPED_DISABLED;
    }
    if (problem.hasDecisions()) {
      // A dispatcher placed or held orders; moving the rest around them would undo the point of keeping them.
      return Trigger.SKIPPED_KEPT_DECISIONS;
    }
    if (rules.decisions().stream().anyMatch(d -> d.decision() == AllocationDecision.DEFERRED)) {
      return Trigger.DEFERRALS;
    }
    BigDecimal floor = problem.rules().optional(MIN_UTILISATION_KEY).orElse(MIN_UTILISATION);
    return utilisation(rules.days()).compareTo(floor) < 0 ? Trigger.LOW_UTILISATION : Trigger.SKIPPED_SIMPLE_DAY;
  }

  /** Average fullness of the trips, by volume or weight, whichever binds. */
  static BigDecimal utilisation(List<VehicleDay> days) {
    double sum = 0;
    int trips = 0;
    for (VehicleDay d : days) {
      for (Trip t : d.trips()) {
        double byVolume = t.volumeM3().doubleValue() / d.vehicle().volumeCapM3().doubleValue();
        double byWeight = t.weightKg().doubleValue() / d.vehicle().weightCapKg().doubleValue();
        sum += Math.max(byVolume, byWeight);
        trips++;
      }
    }
    return trips == 0 ? BigDecimal.ONE : BigDecimal.valueOf(sum / trips).setScale(4, RoundingMode.HALF_UP);
  }

  private int iterations(Problem problem) {
    return problem.rules().optional(ITERATIONS_KEY).map(BigDecimal::intValue).orElse(defaultIterations);
  }

  /** The same orders give the same stream on any machine. */
  private static long seed(Problem problem) {
    long seed = problem.serviceDate().toEpochDay() * 31 + problem.depotCode().hashCode();
    List<UUID> ids = problem.orders().stream().map(PlanOrder::orderId).sorted().toList();
    for (UUID id : ids) {
      seed = seed * 1_000_003L + id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }
    return seed;
  }

  // ---- destroy and repair ------------------------------------------------------

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

  private static void destroy(String op, Map<String, VehicleDay> days, Random random) {
    List<VehicleDay> used = days.values().stream().filter(d -> !d.trips().isEmpty()).toList();
    if (used.isEmpty()) {
      return;
    }
    switch (op) {
      case "vehicle" -> {
        // Empty one vehicle, the emptier ones more often: the way to a plan with fewer vehicles.
        VehicleDay victim = used.stream()
            .min(Comparator.comparingDouble((VehicleDay d) -> fullness(d) * (0.5 + random.nextDouble())))
            .orElseThrow();
        days.put(victim.vehicleId(), VehicleDay.idle(victim.vehicle()));
      }
      case "trip" -> {
        VehicleDay victim = used.get(random.nextInt(used.size()));
        Trip smallest = victim.trips().stream().min(Comparator.comparing(Trip::volumeM3)).orElseThrow();
        VehicleDay rest = victim;
        for (PlanOrder o : smallest.orders()) {
          rest = rest.without(o.orderId());
        }
        days.put(victim.vehicleId(), rest);
      }
      case "related" -> {
        VehicleDay from = used.get(random.nextInt(used.size()));
        String district = from.trips().get(random.nextInt(from.trips().size())).district();
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

  private static double fullness(VehicleDay d) {
    return d.trips().stream().mapToDouble(t -> t.volumeM3().doubleValue()).sum() / d.vehicle().volumeCapM3().doubleValue();
  }

  /**
   * Every unserved order back in rank order (neighbours swapped now and then),
   * each where it adds least: an open trip, then a new trip on a vehicle already
   * out, then a vehicle not yet used; fewest added litres within that, and
   * reefers and vans kept for what only they can carry.
   */
  private void repair(Map<String, VehicleDay> days, List<PlanOrder> ranked, Random random, PlanContext ctx) {
    Set<UUID> served = servedIds(days.values());
    List<PlanOrder> todo = new ArrayList<>(ranked.stream().filter(o -> !served.contains(o.orderId())).toList());
    for (int i = 0; i + 1 < todo.size(); i++) {
      if (random.nextDouble() < 0.15) {
        Collections.swap(todo, i, i + 1);
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
          double scarce = (v.reefer() && o.temperatureClass() != TemperatureClass.CHILLED ? 300 : 0)
              + (v.van() && !o.vanOnly() ? 150 : 0);
          double cost = tier + scarce + FuelLedger.dayLitres(option, ctx.travel()).doubleValue() - before;
          if (cost < bestCost && ConstraintRegistry.allPass(registry.evaluate(new Candidate(option, ctx, Set.of())))) {
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

  // ---- measuring ---------------------------------------------------------------

  private record Measure(Set<UUID> served, int vehicles, int trips, BigDecimal litres) {
    double scalar() {
      return 1000.0 * vehicles + litres.doubleValue();
    }
  }

  private static Measure measure(java.util.Collection<VehicleDay> days, Problem problem) {
    int vehicles = 0;
    int trips = 0;
    BigDecimal litres = BigDecimal.ZERO;
    for (VehicleDay d : days) {
      if (!d.trips().isEmpty()) {
        vehicles++;
        trips += d.trips().size();
        litres = litres.add(FuelLedger.dayLitres(d, problem.travel()));
      }
    }
    return new Measure(servedIds(days), vehicles, trips, litres);
  }

  private static Set<UUID> servedIds(java.util.Collection<VehicleDay> days) {
    Set<UUID> s = new HashSet<>();
    days.forEach(d -> d.trips().forEach(t -> t.orders().forEach(o -> s.add(o.orderId()))));
    return s;
  }

  /** By rank first, then fewer vehicles, then fewer litres. Positive when {@code a} is better. */
  private static int compare(List<PlanOrder> ranked, Measure a, Measure b) {
    int byRank = ScarceFleetReplan.compareByRank(ranked, a.served(), b.served());
    if (byRank != 0) {
      return byRank;
    }
    if (a.vehicles() != b.vehicles()) {
      return a.vehicles() < b.vehicles() ? 1 : -1;
    }
    return b.litres().compareTo(a.litres());
  }

  private static Summary summary(Trigger trigger, boolean improved, Measure before, Measure after, int iterations,
      Stop stop) {
    return new Summary(trigger, improved, before.vehicles(), before.trips(), before.litres().setScale(1, RoundingMode.HALF_UP),
        after.vehicles(), after.trips(), after.litres().setScale(1, RoundingMode.HALF_UP), iterations, stop);
  }
}
