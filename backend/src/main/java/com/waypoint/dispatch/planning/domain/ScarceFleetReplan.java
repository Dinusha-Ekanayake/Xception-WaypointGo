package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The second pass over a greedy plan (issue #92): plan the reefers again as a
 * whole, because they are the vehicles chilled demand runs out of first.
 *
 * <p>The greedy places orders one at a time and never revisits a reefer trip,
 * so a reefer can spend its pre-dawn Fresh budget on one small far load while
 * larger chilled orders wait. One order moved at a time cannot repair that: the
 * better plan changes several reefer trips together. So this pass:
 *
 * <ol>
 *   <li><b>Ruins</b> every reefer day and pools its orders with the deferred
 *       ones.
 *   <li><b>Lists</b> each reefer's feasible days of one or two chilled trips,
 *       every one checked through the {@link ConstraintRegistry}, keeping only
 *       days no other day of the same vehicle covers entirely.
 *   <li><b>Searches</b> one day per reefer for the best set of chilled orders
 *       served by rank (R-PLN-21): orders are admitted highest rank first, each
 *       only if the reefers can still serve every order admitted before it.
 *   <li><b>Recreates</b> the rest by {@link CheapestInsertion} in priority order,
 *       and explains every deferral against the final plan (R-PLN-19).
 *   <li><b>Keeps</b> the result only if it is strictly better than the greedy
 *       plan by rank, so it can never defer a higher ranked order to serve a
 *       lower one, and never makes a plan worse (R-PLN-32).
 * </ol>
 *
 * <p>Pure and deterministic: every list is in a stable order, there is no
 * randomness, and the search stops on a node budget that is part of the engine
 * version, so the same inputs give the same plan on any machine. The clock is a
 * safety stop only; the summary says when it fired.
 */
public final class ScarceFleetReplan {
  public static final String NAME = "scarce-replan-v1";

  /** The search budget of this engine version. Changing it is a new engine name. */
  public static final long MAX_NODES = 2_000_000L;

  /** The most chilled orders the search ranks at once (one bit each); the rest go to insertion. */
  public static final int MAX_POOL = 62;

  public enum Stop {
    NONE,
    NODES,
    CLOCK
  }

  /**
   * @param improved whether the pass replaced the greedy plan
   * @param chilledVolumeGainedM3 chilled volume served beyond the greedy plan
   * @param chilledCandidates chilled orders the reefers could have taken
   * @param chilledSearched how many of them the search ranked; fewer than the
   *     candidates means the lowest ranked were left to insertion, and the
   *     screen says so (rule 9)
   */
  public record Summary(
      int greedyServed,
      int greedyDeferred,
      int served,
      int deferred,
      boolean improved,
      java.math.BigDecimal chilledVolumeGainedM3,
      long nodes,
      Stop stoppedBy,
      int chilledCandidates,
      int chilledSearched) {}

  public record Result(AllocationResult allocation, Summary summary) {}

  private final ConstraintRegistry registry;
  private final CheapestInsertion insertion;
  private final long maxNodes;
  private final int maxPool;

  public ScarceFleetReplan(ConstraintRegistry registry) {
    this(registry, MAX_NODES, MAX_POOL);
  }

  ScarceFleetReplan(ConstraintRegistry registry, long maxNodes) {
    this(registry, maxNodes, MAX_POOL);
  }

  ScarceFleetReplan(ConstraintRegistry registry, long maxNodes, int maxPool) {
    if (maxPool < 1 || maxPool > MAX_POOL) {
      throw new IllegalArgumentException("the search ranks between 1 and " + MAX_POOL + " orders, one bit each");
    }
    this.registry = registry;
    this.insertion = new CheapestInsertion(registry);
    this.maxNodes = maxNodes;
    this.maxPool = maxPool;
  }

  /**
   * @param nanoTime the clock, read only against {@code deadlineNanos}
   * @param engine the name the result carries
   */
  public Result improve(
      Problem problem, AllocationResult greedy, LongSupplier nanoTime, long deadlineNanos, String engine) {
    PlanContext ctx = problem.context();
    Map<UUID, PlanOrder> byId = new HashMap<>();
    problem.orders().forEach(o -> byId.put(o.orderId(), o));

    Set<UUID> unservable = new HashSet<>();
    Set<UUID> greedyServed = new HashSet<>();
    for (OrderDecision d : greedy.decisions()) {
      if (d.decision() == AllocationDecision.UNSERVABLE) {
        unservable.add(d.orderId());
      } else if (d.decision() == AllocationDecision.SERVED) {
        greedyServed.add(d.orderId());
      }
    }
    List<PlanOrder> ranked =
        problem.policy().rank(
            problem.orders().stream().filter(o -> !unservable.contains(o.orderId())).toList(), ctx);
    Summary unchanged =
        summary(greedyServed.size(), ranked.size() - greedyServed.size(), greedyServed.size(),
            ranked.size() - greedyServed.size(), false, java.math.BigDecimal.ZERO, 0, Stop.NONE, 0, 0);

    // Every available vehicle, idle unless the greedy plan used it.
    Map<String, VehicleDay> days = new TreeMap<>();
    problem.fleet().stream().filter(FleetVehicle::available).forEach(v -> days.put(v.vehicleId(), VehicleDay.idle(v)));
    greedy.days().forEach(d -> days.put(d.vehicleId(), d));
    List<FleetVehicle> reefers = days.values().stream().map(VehicleDay::vehicle).filter(FleetVehicle::reefer).toList();
    if (reefers.isEmpty()) {
      return new Result(greedy, unchanged);
    }

    // Ruin: the reefers' orders and every deferral, chilled ones ranked for the search.
    Set<UUID> pool = new HashSet<>();
    for (FleetVehicle v : reefers) {
      days.get(v.vehicleId()).trips().forEach(t -> t.orders().forEach(o -> pool.add(o.orderId())));
      days.put(v.vehicleId(), VehicleDay.idle(v));
    }
    ranked.stream().filter(o -> !greedyServed.contains(o.orderId())).forEach(o -> pool.add(o.orderId()));
    List<PlanOrder> candidates =
        ranked.stream()
            .filter(o -> pool.contains(o.orderId()) && o.temperatureClass() == TemperatureClass.CHILLED)
            .toList();
    // Highest ranked first, so the orders left to insertion are the ones the rank comparison weighs least.
    List<PlanOrder> chilled = candidates.subList(0, Math.min(candidates.size(), maxPool));
    if (chilled.isEmpty()) {
      return new Result(greedy, unchanged);
    }
    unchanged = withPool(unchanged, candidates.size(), chilled.size());
    Map<UUID, Integer> bit = new HashMap<>();
    for (int i = 0; i < chilled.size(); i++) {
      // The highest ranked order is the most significant bit, so a larger mask is a better plan by rank.
      bit.put(chilled.get(i).orderId(), chilled.size() - 1 - i);
    }

    // List each reefer's days, then search one per reefer.
    List<List<DayPlan>> plans = new ArrayList<>();
    for (FleetVehicle v : reefers) {
      plans.add(dayPlans(v, chilled, bit, ctx));
    }
    Search search = new Search(plans, maxNodes, nanoTime, deadlineNanos);
    long greedyMask = 0;
    for (PlanOrder o : chilled) {
      if (greedyServed.contains(o.orderId())) {
        greedyMask |= 1L << bit.get(o.orderId());
      }
    }
    List<Long> bits = new ArrayList<>();
    for (PlanOrder o : chilled) {
      bits.add(1L << bit.get(o.orderId()));
    }
    search.run(bits, greedyMask);
    if (search.choice == null) {
      return new Result(greedy, summary(unchanged, search));
    }

    // Recreate: the chosen reefer days, then everything else by priority.
    Set<UUID> taken = new HashSet<>();
    for (int i = 0; i < reefers.size(); i++) {
      DayPlan chosen = search.choice[i];
      if (chosen == null) {
        continue;
      }
      VehicleDay day = chosen.day();
      for (Trip t : chosen.day().trips()) {
        for (PlanOrder o : t.orders()) {
          if (!taken.add(o.orderId())) {
            day = day.without(o.orderId());
          }
        }
      }
      if (!feasible(day, ctx)) {
        // Dropping a duplicate stop changed a timing; keep the greedy plan rather than guess.
        return new Result(greedy, summary(unchanged, search));
      }
      days.put(reefers.get(i).vehicleId(), day);
    }
    Set<UUID> carried = new HashSet<>();
    days.values().forEach(d -> d.trips().forEach(t -> t.orders().forEach(o -> carried.add(o.orderId()))));
    for (PlanOrder o : ranked) {
      if (pool.contains(o.orderId()) && !carried.contains(o.orderId())) {
        insertion.insertOrDefer(days, o, ctx);
      }
    }

    // Decide every order against the final plan, then keep it only if strictly better by rank.
    List<OrderDecision> decisions = new ArrayList<>();
    List<PlanOrder> deferredNow = new ArrayList<>();
    Set<UUID> servedNow = new HashSet<>();
    days.values().forEach(d -> d.trips().forEach(t -> t.orders().forEach(o -> servedNow.add(o.orderId()))));
    for (PlanOrder o : ranked) {
      if (servedNow.contains(o.orderId())) {
        decisions.add(insertion.served(o, days.values(), ctx));
      } else {
        deferredNow.add(o);
      }
    }
    for (PlanOrder o : deferredNow) {
      decisions.add(CheapestInsertion.deferred(o, insertion.place(days.values(), o, ctx).closest()));
    }
    greedy.decisions().stream().filter(d -> unservable.contains(d.orderId())).forEach(decisions::add);
    decisions.sort(Comparator.comparing(OrderDecision::orderId));

    if (compareByRank(ranked, servedNow, greedyServed) <= 0) {
      return new Result(greedy, summary(unchanged, search));
    }
    java.math.BigDecimal gained =
        volume(ranked, servedNow, TemperatureClass.CHILLED).subtract(volume(ranked, greedyServed, TemperatureClass.CHILLED));
    Summary summary =
        summary(greedyServed.size(), ranked.size() - greedyServed.size(), servedNow.size(), deferredNow.size(), true,
            gained, search.nodes, search.stoppedBy, candidates.size(), chilled.size());
    return new Result(
        new AllocationResult(List.copyOf(days.values()), decisions, false, engine, java.util.Optional.of(summary)),
        summary);
  }

  /**
   * Compares two served sets by rank: the highest ranked order that one serves
   * and the other does not decides. Positive when {@code a} is better.
   */
  static int compareByRank(List<PlanOrder> ranked, Set<UUID> a, Set<UUID> b) {
    for (PlanOrder o : ranked) {
      boolean inA = a.contains(o.orderId());
      if (inA != b.contains(o.orderId())) {
        return inA ? 1 : -1;
      }
    }
    return 0;
  }

  // ---- listing a reefer's days ----------------------------------------------

  private record TripOption(Trip trip, long mask) {}

  record DayPlan(VehicleDay day, long mask) {}

  /** Every feasible day of one or two chilled trips, without the days another day covers. */
  private List<DayPlan> dayPlans(FleetVehicle v, List<PlanOrder> chilled, Map<UUID, Integer> bit, PlanContext ctx) {
    Map<String, List<PlanOrder>> groups = new LinkedHashMap<>();
    for (PlanOrder o : chilled) {
      groups.computeIfAbsent(o.brand() + "|" + o.district() + "|" + o.temperatureClass(), k -> new ArrayList<>()).add(o);
    }
    List<TripOption> trips = new ArrayList<>();
    for (List<PlanOrder> group : groups.values()) {
      subsets(v, group, 0, null, 0L, bit, ctx, trips);
    }

    List<DayPlan> days = new ArrayList<>();
    for (TripOption t : trips) {
      days.add(new DayPlan(new VehicleDay(v, List.of(t.trip())), t.mask()));
    }
    if (ctx.rules().maxTrips() >= 2) {
      for (int i = 0; i < trips.size(); i++) {
        for (int j = i + 1; j < trips.size(); j++) {
          TripOption a = trips.get(i);
          TripOption b = trips.get(j);
          if ((a.mask() & b.mask()) != 0) {
            continue;
          }
          // Two Fresh trips run in the order given, so try both.
          VehicleDay ab = new VehicleDay(v, List.of(a.trip(), b.trip()));
          VehicleDay ba = new VehicleDay(v, List.of(b.trip(), a.trip()));
          VehicleDay feasible = feasible(ab, ctx) ? ab : feasible(ba, ctx) ? ba : null;
          if (feasible != null) {
            days.add(new DayPlan(feasible, a.mask() | b.mask()));
          }
        }
      }
    }
    return maximal(days);
  }

  /**
   * Every subset of one group that fits as a single trip. A subset that fails
   * is not extended: capacity, time and fuel only get worse as orders join.
   */
  private void subsets(
      FleetVehicle v, List<PlanOrder> group, int from, Trip current, long mask, Map<UUID, Integer> bit,
      PlanContext ctx, List<TripOption> out) {
    for (int i = from; i < group.size(); i++) {
      PlanOrder o = group.get(i);
      Trip next = current == null ? Trip.of(o) : current.with(o);
      if (!feasible(new VehicleDay(v, List.of(next)), ctx)) {
        continue;
      }
      long nextMask = mask | (1L << bit.get(o.orderId()));
      out.add(new TripOption(next, nextMask));
      subsets(v, group, i + 1, next, nextMask, bit, ctx, out);
    }
  }

  private boolean feasible(VehicleDay day, PlanContext ctx) {
    return ConstraintRegistry.allPass(registry.evaluate(new Candidate(day, ctx, Set.of())));
  }

  /** Best first, and without any day whose orders another day of the same vehicle covers. */
  private static List<DayPlan> maximal(List<DayPlan> days) {
    List<DayPlan> sorted = new ArrayList<>(days);
    sorted.sort(
        Comparator.comparingInt((DayPlan d) -> -Long.bitCount(d.mask()))
            .thenComparing(DayPlan::mask, Comparator.reverseOrder()));
    List<DayPlan> kept = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (DayPlan d : sorted) {
      if (!seen.add(d.mask())) {
        continue;
      }
      boolean covered = false;
      for (DayPlan k : kept) {
        if ((d.mask() & k.mask()) == d.mask()) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        kept.add(d);
      }
    }
    kept.sort(Comparator.comparing(DayPlan::mask, Comparator.reverseOrder()));
    return kept;
  }

  // ---- the search ------------------------------------------------------------

  /**
   * The best set of chilled orders the reefers can serve together, decided in
   * rank order: an order joins the set only if every order already in it can
   * still be served with it. That is the rank comparison itself, so the set is
   * the best by rank, not merely a good one.
   *
   * <p>Each question is a covering search: take the highest order not yet
   * covered and try every reefer day that serves it. A day may serve orders
   * another day also serves; the duplicate is dropped when the plan is built.
   * A state that failed (which reefers are left, which orders are still owed)
   * fails again, so it is remembered.
   */
  private static final class Search {
    private final List<List<DayPlan>> plans;
    private final long maxNodes;
    private final LongSupplier nanoTime;
    private final long deadline;
    private final int vehicles;

    long nodes;
    Stop stoppedBy = Stop.NONE;
    DayPlan[] choice;

    private Set<Long>[] failed;
    private DayPlan[] current;

    Search(List<List<DayPlan>> plans, long maxNodes, LongSupplier nanoTime, long deadline) {
      this.plans = plans;
      this.maxNodes = maxNodes;
      this.nanoTime = nanoTime;
      this.deadline = deadline;
      this.vehicles = plans.size();
    }

    /**
     * @param bits the chilled orders' bits, highest rank first
     * @param floor the greedy's mask: a set no better than it is not returned
     */
    @SuppressWarnings("unchecked")
    void run(List<Long> bits, long floor) {
      long required = 0;
      DayPlan[] found = null;
      for (long b : bits) {
        long trial = required | b;
        failed = new Set[1 << vehicles];
        current = new DayPlan[vehicles];
        if (cover(trial, (1 << vehicles) - 1)) {
          required = trial;
          found = current.clone();
        }
        if (stoppedBy != Stop.NONE) {
          break;
        }
      }
      if (found != null && required > floor) {
        choice = found;
      }
    }

    /** Whether the reefers in {@code free} can serve every order in {@code owed}. */
    private boolean cover(long owed, int free) {
      if (owed == 0) {
        return true;
      }
      if (++nodes > maxNodes) {
        stoppedBy = Stop.NODES;
        return false;
      }
      if ((nodes & 1023) == 0 && nanoTime.getAsLong() > deadline) {
        stoppedBy = Stop.CLOCK;
        return false;
      }
      if (failed[free] != null && failed[free].contains(owed)) {
        return false;
      }
      long highest = Long.highestOneBit(owed);
      for (int k = 0; k < vehicles; k++) {
        if ((free & (1 << k)) == 0) {
          continue;
        }
        Set<Long> tried = new HashSet<>();
        for (DayPlan d : plans.get(k)) {
          long serves = d.mask() & owed;
          if ((serves & highest) == 0 || !tried.add(serves)) {
            continue;
          }
          current[k] = d;
          if (cover(owed & ~serves, free & ~(1 << k))) {
            return true;
          }
          current[k] = null;
          if (stoppedBy != Stop.NONE) {
            return false;
          }
        }
      }
      if (failed[free] == null) {
        failed[free] = new HashSet<>();
      }
      failed[free].add(owed);
      return false;
    }
  }

  // ---- summary -----------------------------------------------------------------

  private static java.math.BigDecimal volume(List<PlanOrder> ranked, Set<UUID> served, TemperatureClass temperature) {
    return ranked.stream()
        .filter(o -> served.contains(o.orderId()) && o.temperatureClass() == temperature)
        .map(PlanOrder::volumeM3)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
  }

  private static Summary summary(
      int greedyServed, int greedyDeferred, int served, int deferred, boolean improved,
      java.math.BigDecimal gained, long nodes, Stop stop, int candidates, int searched) {
    return new Summary(greedyServed, greedyDeferred, served, deferred, improved, gained, nodes, stop, candidates, searched);
  }

  private static Summary withPool(Summary s, int candidates, int searched) {
    return new Summary(
        s.greedyServed(), s.greedyDeferred(), s.served(), s.deferred(), s.improved(), s.chilledVolumeGainedM3(),
        s.nodes(), s.stoppedBy(), candidates, searched);
  }

  private static Summary summary(Summary unchanged, Search search) {
    return new Summary(
        unchanged.greedyServed(), unchanged.greedyDeferred(), unchanged.served(), unchanged.deferred(), false,
        java.math.BigDecimal.ZERO, search.nodes, search.stoppedBy, unchanged.chilledCandidates(),
        unchanged.chilledSearched());
  }
}
