package com.waypoint.dispatch.planning.bench;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * E2: the exact engine. Orders onto (vehicle, trip slot) as a MIP: one brand,
 * district and temperature per trip, capacity, the 270/480 minute budgets and
 * the fuel quota as linear constraints. Delivery windows depend on the stop
 * sequence, so they are checked by the production {@code ConstraintRegistry}
 * after each solve, and a failing combination is cut off and the model solved
 * again (logic-based Benders).
 *
 * <p>Priority is exact (R-PLN-21, R-PLN-32): orders are admitted in rank order,
 * each only if every order admitted before it can still be served with it. A
 * second solve then serves exactly that set with the fewest vehicles, then the
 * fewest litres. The greedy plan is the first incumbent, so the engine is never
 * worse than it.
 */
final class MipEngine implements AllocationEngine {
  private static final double EPS = 1e-6;
  private static final Set<String> WINDOW_RULES = Set.of("R-PLN-13", "R-PLN-29", "R-PLN-30");

  private final String backend;
  private final long budgetMs;

  /** What happened, for the table. */
  int solves;
  int cuts;
  boolean stoppedByBudget;
  /** Orders left out because time ran out, not because the solver proved they cannot fit. */
  int unproven;
  /** Whether the cost stage finished. */
  boolean costDone;
  private boolean lastTimedOut;
  /** The cost stage's objective and the solver's bound on it: vehicles x 1000 + litres. */
  double lastCost = Double.NaN;
  double lastBound = Double.NaN;

  private final boolean windows;

  MipEngine(String backend, long budgetMs) {
    this(backend, budgetMs, true);
  }

  MipEngine(String backend, long budgetMs, boolean windows) {
    Loader.loadNativeLibraries();
    this.backend = backend;
    this.budgetMs = budgetMs;
    this.windows = windows;
  }

  @Override
  public String name() {
    return "E2-mip-" + backend.toLowerCase() + "-" + budgetMs / 1000 + "s";
  }

  @Override
  public AllocationResult allocate(Problem p) {
    long deadline = System.nanoTime() + budgetMs * 1_000_000L;
    solves = 0;
    cuts = 0;
    stoppedByBudget = false;
    unproven = 0;
    costDone = false;
    AllocationResult greedy = new ImprovingEngine(new PriorityInsertionEngine(Bench.REGISTRY), Bench.REGISTRY).allocate(p);
    List<OrderDecision> unservable = greedy.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.UNSERVABLE).toList();
    Set<UUID> unservableIds = new HashSet<>();
    unservable.forEach(d -> unservableIds.add(d.orderId()));
    Model m = new Model(p, Bench.ranked(p, unservableIds));

    Map<String, VehicleDay> incumbent = new TreeMap<>();
    greedy.days().forEach(d -> incumbent.put(d.vehicleId(), d));
    Set<Integer> required = new HashSet<>();
    Set<Integer> forbidden = new HashSet<>();
    for (int i = 0; i < m.orders.size(); i++) {
      if (Bench.servedIds(incumbent.values()).contains(m.orders.get(i).orderId())) {
        required.add(i);
        continue;
      }
      if (System.nanoTime() > deadline) {
        stoppedByBudget = true;
        unproven++;
        forbidden.add(i);
        continue;
      }
      Set<Integer> trial = new HashSet<>(required);
      trial.add(i);
      Optional<Map<String, VehicleDay>> found = m.solve(trial, forbidden, false, incumbent, deadline);
      if (found.isPresent()) {
        incumbent.clear();
        incumbent.putAll(found.get());
        required.add(i);
      } else {
        // Not servable with every higher ranked order it must not displace; it stays out from now on.
        if (lastTimedOut) {
          unproven++;
        }
        forbidden.add(i);
      }
    }
    // The same orders, on the fewest vehicles, then the fewest litres.
    if (System.nanoTime() < deadline) {
      Set<Integer> rest = new HashSet<>();
      for (int i = 0; i < m.orders.size(); i++) {
        if (!required.contains(i)) {
          rest.add(i);
        }
      }
      m.solve(required, rest, true, incumbent, deadline).ifPresent(cheaper -> {
        if (costOf(cheaper.values(), p) < costOf(incumbent.values(), p)) {
          incumbent.clear();
          incumbent.putAll(cheaper);
        }
        costDone = !lastTimedOut;
      });
    } else {
      stoppedByBudget = true;
    }
    return Bench.finish(p, incumbent.values(), unservable, name());
  }

  /** Vehicles x 1000 + litres, the cost stage's objective. */
  static double costOf(java.util.Collection<VehicleDay> days, Problem p) {
    double cost = 0;
    for (VehicleDay d : days) {
      if (!d.trips().isEmpty()) {
        cost += 1000 + com.waypoint.dispatch.planning.domain.FuelLedger.dayLitres(d, p.travel()).doubleValue();
      }
    }
    return cost;
  }

  // ---- the model -------------------------------------------------------------

  private record Key(int order, int vehicle, int slot) {}

  private record GroupKey(String group, int vehicle, int slot) {}

  /** A combination that failed the registry: these orders on these (vehicle, slot)s, or anywhere when vehicle is -1. */
  private record Cut(int vehicle, List<int[]> placements) {}

  private final class Model {
    final Problem p;
    final PlanContext ctx;
    final List<PlanOrder> orders;
    final List<FleetVehicle> vehicles;
    final int slots;
    final List<Cut> cutList = new ArrayList<>();
    final Map<UUID, Integer> index = new HashMap<>();
    /** The last solution's trips by the solver's own slots, before VehicleDay puts Fresh first. */
    final Map<String, List<List<PlanOrder>>> lastSlots = new HashMap<>();

    Model(Problem p, List<PlanOrder> ranked) {
      this.p = p;
      this.ctx = p.context();
      this.orders = ranked;
      this.vehicles = p.fleet().stream().filter(FleetVehicle::available)
          .sorted(java.util.Comparator.comparing((FleetVehicle v) -> signature(v)).thenComparing(FleetVehicle::vehicleId))
          .toList();
      this.slots = p.rules().maxTrips();
      for (int i = 0; i < orders.size(); i++) {
        index.put(orders.get(i).orderId(), i);
      }
    }

    boolean eligible(PlanOrder o, FleetVehicle v) {
      return (o.temperatureClass() != TemperatureClass.CHILLED || v.reefer())
          && (!o.vanOnly() || v.van())
          && o.weightKg().doubleValue() <= v.weightCapKg().doubleValue() + EPS
          && o.volumeM3().doubleValue() <= v.volumeCapM3().doubleValue() + EPS;
    }

    String signature(FleetVehicle v) {
      return v.van() + "|" + v.reefer() + "|" + v.weightCapKg() + "|" + v.volumeCapM3() + "|" + v.kmPerL() + "|"
          + v.weeklyFuelQuotaL() + "|" + v.fuelUsedThisWeekL();
    }

    String group(PlanOrder o) {
      return o.brand() + "|" + o.district() + "|" + o.temperatureClass();
    }

    /**
     * Serves every order in {@code required}, none in {@code forbidden}, and
     * passes the registry on every vehicle; empty when no such plan exists or
     * time ran out.
     */
    Optional<Map<String, VehicleDay>> solve(Set<Integer> required, Set<Integer> forbidden, boolean cost,
        Map<String, VehicleDay> hint, long deadline) {
      lastTimedOut = false;
      for (int round = 0; round < 400; round++) {
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) {
          stoppedByBudget = true;
          lastTimedOut = true;
          return Optional.empty();
        }
        Optional<Map<String, VehicleDay>> plan = solveOnce(required, forbidden, cost, hint, left);
        if (plan.isEmpty()) {
          return Optional.empty();
        }
        if (!addCuts(plan.get())) {
          return plan;
        }
      }
      return Optional.empty();
    }

    private Optional<Map<String, VehicleDay>> solveOnce(Set<Integer> required, Set<Integer> forbidden, boolean cost,
        Map<String, VehicleDay> hint, long timeLimitMs) {
      solves++;
      MPSolver s = MPSolver.createSolver(backend);
      s.setTimeLimit(timeLimitMs);
      if ("CP_SAT".equals(backend)) {
        s.setSolverSpecificParametersAsString("num_workers:1 random_seed:1");
      } else if ("HIGHS".equals(backend)) {
        s.setSolverSpecificParametersAsString("threads=1\nrandom_seed=1\n");
      }
      s.setNumThreads(1);
      Map<Key, MPVariable> x = new LinkedHashMap<>();
      Map<GroupKey, MPVariable> y = new LinkedHashMap<>();
      MPObjective obj = s.objective();

      for (int vi = 0; vi < vehicles.size(); vi++) {
        FleetVehicle v = vehicles.get(vi);
        for (int t = 0; t < slots; t++) {
          for (int oi = 0; oi < orders.size(); oi++) {
            PlanOrder o = orders.get(oi);
            if (forbidden.contains(oi) || !eligible(o, v)) {
              continue;
            }
            x.put(new Key(oi, vi, t), s.makeBoolVar("x" + oi + "_" + vi + "_" + t));
            y.computeIfAbsent(new GroupKey(group(o), vi, t), k -> s.makeBoolVar("y" + k));
          }
        }
      }
      // Each order at most once; required ones exactly once.
      Map<Integer, MPConstraint> once = new HashMap<>();
      for (int oi = 0; oi < orders.size(); oi++) {
        if (forbidden.contains(oi)) {
          continue;
        }
        double lo = required.contains(oi) ? 1 : 0;
        once.put(oi, s.makeConstraint(lo, 1, "once" + oi));
      }
      x.forEach((k, var) -> once.get(k.order()).setCoefficient(var, 1));
      for (int oi : required) {
        if (!once.containsKey(oi)) {
          return Optional.empty();
        }
      }
      // An order rides only in a trip of its group; one group per trip; a group is opened only with an order.
      Map<GroupKey, MPConstraint> groupHasOrder = new HashMap<>();
      y.forEach((g, var) -> groupHasOrder.put(g, s.makeConstraint(Double.NEGATIVE_INFINITY, 0, "")));
      y.forEach((g, var) -> groupHasOrder.get(g).setCoefficient(var, 1));
      x.forEach((k, var) -> {
        GroupKey g = new GroupKey(group(orders.get(k.order())), k.vehicle(), k.slot());
        MPConstraint link = s.makeConstraint(Double.NEGATIVE_INFINITY, 0, "");
        link.setCoefficient(var, 1);
        link.setCoefficient(y.get(g), -1);
        groupHasOrder.get(g).setCoefficient(var, -1);
      });
      for (int vi = 0; vi < vehicles.size(); vi++) {
        FleetVehicle v = vehicles.get(vi);
        MPConstraint[] oneGroup = new MPConstraint[slots];
        MPConstraint[] weight = new MPConstraint[slots];
        MPConstraint[] volume = new MPConstraint[slots];
        for (int t = 0; t < slots; t++) {
          oneGroup[t] = s.makeConstraint(Double.NEGATIVE_INFINITY, 1, "");
          weight[t] = s.makeConstraint(Double.NEGATIVE_INFINITY, v.weightCapKg().doubleValue() + EPS, "");
          volume[t] = s.makeConstraint(Double.NEGATIVE_INFINITY, v.volumeCapM3().doubleValue() + EPS, "");
        }
        MPConstraint slotOrder = slots > 1 ? s.makeConstraint(Double.NEGATIVE_INFINITY, 0, "") : null;
        MPConstraint fresh = s.makeConstraint(Double.NEGATIVE_INFINITY, p.rules().freshBudgetMinutes().doubleValue() + EPS, "");
        MPConstraint day = s.makeConstraint(Double.NEGATIVE_INFINITY, p.rules().daytimeBudgetMinutes().doubleValue() + EPS, "");
        double fuelLeft = v.weeklyFuelQuotaL().subtract(v.fuelUsedThisWeekL()).doubleValue();
        MPConstraint fuel = s.makeConstraint(Double.NEGATIVE_INFINITY, fuelLeft + 1e-3, "");
        MPVariable used = cost ? s.makeBoolVar("u" + vi) : null;
        double kmpl = v.kmPerL().doubleValue();
        final int vIndex = vi;
        for (Map.Entry<GroupKey, MPVariable> e : y.entrySet()) {
          GroupKey g = e.getKey();
          if (g.vehicle() != vIndex) {
            continue;
          }
          String[] parts = g.group().split("\\|");
          DistrictTravel d = p.travel().get(parts[1]);
          boolean isFresh = "Fresh".equalsIgnoreCase(parts[0]);
          oneGroup[g.slot()].setCoefficient(e.getValue(), 1);
          (isFresh ? fresh : day).setCoefficient(e.getValue(),
              d.outboundMinutes().doubleValue() - d.interStopMinutes().doubleValue());
          fuel.setCoefficient(e.getValue(),
              (2 * d.outboundKm().doubleValue() - d.interStopKm().doubleValue()) / kmpl);
          if (slotOrder != null) {
            slotOrder.setCoefficient(e.getValue(), g.slot() == 1 ? 1 : g.slot() == 0 ? -1 : 0);
          }
          if (used != null) {
            MPConstraint u = s.makeConstraint(Double.NEGATIVE_INFINITY, 0, "");
            u.setCoefficient(e.getValue(), 1);
            u.setCoefficient(used, -1);
          }
        }
        for (Map.Entry<Key, MPVariable> e : x.entrySet()) {
          Key k = e.getKey();
          if (k.vehicle() != vIndex) {
            continue;
          }
          PlanOrder o = orders.get(k.order());
          DistrictTravel d = p.travel().get(o.district());
          weight[k.slot()].setCoefficient(e.getValue(), o.weightKg().doubleValue());
          volume[k.slot()].setCoefficient(e.getValue(), o.volumeM3().doubleValue());
          (o.fresh() ? fresh : day).setCoefficient(e.getValue(),
              d.interStopMinutes().doubleValue() + o.serviceMinutes().doubleValue());
          fuel.setCoefficient(e.getValue(), d.interStopKm().doubleValue() / kmpl);
        }
        if (used != null) {
          obj.setCoefficient(used, 1000);
          // Litres, as the fuel row counts them.
          for (Map.Entry<GroupKey, MPVariable> e : y.entrySet()) {
            if (e.getKey().vehicle() == vIndex) {
              DistrictTravel d = p.travel().get(e.getKey().group().split("\\|")[1]);
              obj.setCoefficient(e.getValue(), (2 * d.outboundKm().doubleValue() - d.interStopKm().doubleValue()) / kmpl);
            }
          }
          for (Map.Entry<Key, MPVariable> e : x.entrySet()) {
            if (e.getKey().vehicle() == vIndex) {
              DistrictTravel d = p.travel().get(orders.get(e.getKey().order()).district());
              obj.setCoefficient(e.getValue(), d.interStopKm().doubleValue() / kmpl);
            }
          }
        }
      }
      // Deadlines, as a necessary condition: in any stop order, the stops of a trip due by an order's
      // close (its own included) cannot all be reached later than the depot departure, the outbound
      // leg and every stop of that set but the last allow. Waiting only makes arrivals later.
      if (windows) {
        double big = 24 * 60 * 4;
        x.forEach((k, var) -> {
          PlanOrder o = orders.get(k.order());
          if (o.windowClose().isEmpty()) {
            return;
          }
          DistrictTravel d = p.travel().get(o.district());
          double base = (o.fresh() ? p.rules().freshDeparture() : p.rules().daytimeDeparture()).toSecondOfDay() / 60.0;
          double close = o.windowClose().get().toSecondOfDay() / 60.0;
          double inter = d.interStopMinutes().doubleValue();
          String g = group(o);
          double maxStep = 0;
          List<Map.Entry<MPVariable, Double>> earlier = new ArrayList<>();
          for (Map.Entry<Key, MPVariable> e : x.entrySet()) {
            Key q = e.getKey();
            if (q.vehicle() != k.vehicle() || q.slot() != k.slot() || q.order() == k.order()) {
              continue;
            }
            PlanOrder other = orders.get(q.order());
            if (!group(other).equals(g) || other.windowClose().isEmpty()
                || other.windowClose().get().isAfter(o.windowClose().get())) {
              continue;
            }
            double step = other.serviceMinutes().doubleValue() + inter;
            maxStep = Math.max(maxStep, step);
            earlier.add(Map.entry(e.getValue(), step));
          }
          if (earlier.isEmpty()) {
            return;
          }
          // Sum of steps over the set, less the largest step (the set's last stop may be any of them).
          double own = o.serviceMinutes().doubleValue() + inter;
          double slack = Math.max(maxStep, own) - own;
          MPConstraint row = s.makeConstraint(Double.NEGATIVE_INFINITY,
              close - base - d.outboundMinutes().doubleValue() + big + slack, "");
          earlier.forEach(en -> row.setCoefficient(en.getKey(), en.getValue()));
          row.setCoefficient(var, big);
          if (k.slot() == 1) {
            // A second trip leaves when the first one ends: no earlier than 03:30 plus the first trip's minutes.
            double first = p.rules().freshDeparture().toSecondOfDay() / 60.0;
            MPConstraint after = s.makeConstraint(Double.NEGATIVE_INFINITY,
                close - first - d.outboundMinutes().doubleValue() + big + slack, "");
            earlier.forEach(en -> after.setCoefficient(en.getKey(), en.getValue()));
            after.setCoefficient(var, big);
            y.forEach((gk, yv) -> {
              if (gk.vehicle() == k.vehicle() && gk.slot() == 0) {
                DistrictTravel gd = p.travel().get(gk.group().split("\\|")[1]);
                after.setCoefficient(yv, gd.outboundMinutes().doubleValue() - gd.interStopMinutes().doubleValue());
              }
            });
            x.forEach((xk, xv) -> {
              if (xk.vehicle() == k.vehicle() && xk.slot() == 0) {
                PlanOrder q = orders.get(xk.order());
                after.setCoefficient(xv,
                    p.travel().get(q.district()).interStopMinutes().doubleValue() + q.serviceMinutes().doubleValue());
              }
            });
          }
        });
        // Fresh trips run first, as a vehicle's day does: no Fresh trip in slot 1 behind a daytime one in slot 0.
        y.forEach((a, ya) -> {
          if (a.slot() != 1 || !a.group().startsWith("Fresh|")) {
            return;
          }
          y.forEach((b, yb) -> {
            if (b.vehicle() == a.vehicle() && b.slot() == 0 && !b.group().startsWith("Fresh|")) {
              MPConstraint row = s.makeConstraint(Double.NEGATIVE_INFINITY, 1, "");
              row.setCoefficient(ya, 1);
              row.setCoefficient(yb, 1);
            }
          });
        });
      }
      // Identical vehicles are interchangeable: the earlier one carries at least as many orders.
      for (int vi = 0; vi + 1 < vehicles.size(); vi++) {
        if (!signature(vehicles.get(vi)).equals(signature(vehicles.get(vi + 1)))) {
          continue;
        }
        MPConstraint row = s.makeConstraint(0, Double.POSITIVE_INFINITY, "");
        final int a = vi;
        x.forEach((k, var) -> {
          if (k.vehicle() == a) {
            row.setCoefficient(var, 1);
          } else if (k.vehicle() == a + 1) {
            row.setCoefficient(var, -1);
          }
        });
      }
      // What the registry refused before.
      for (Cut c : cutList) {
        for (int vi = 0; vi < vehicles.size(); vi++) {
          if (c.vehicle() >= 0 && c.vehicle() != vi) {
            continue;
          }
          for (int t = 0; t < (c.vehicle() >= 0 ? 1 : slots); t++) {
            MPConstraint row = s.makeConstraint(Double.NEGATIVE_INFINITY, c.placements().size() - 1, "");
            boolean all = true;
            for (int[] pl : c.placements()) {
              MPVariable var = x.get(new Key(pl[0], vi, c.vehicle() >= 0 ? pl[1] : t));
              if (var == null) {
                all = false;
                break;
              }
              row.setCoefficient(var, 1);
            }
            if (!all) {
              row.setBounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
          }
        }
      }
      if (!cost) {
        // Admission asks only whether a plan exists.
        obj.setMaximization();
      } else {
        obj.setMinimization();
      }
      // The incumbent as a hint.
      List<MPVariable> hv = new ArrayList<>();
      List<Double> hval = new ArrayList<>();
      for (int vi = 0; vi < vehicles.size(); vi++) {
        VehicleDay d = hint.get(vehicles.get(vi).vehicleId());
        if (d == null) {
          continue;
        }
        for (int t = 0; t < d.trips().size() && t < slots; t++) {
          for (PlanOrder o : d.trips().get(t).orders()) {
            Integer oi = index.get(o.orderId());
            MPVariable var = oi == null ? null : x.get(new Key(oi, vi, t));
            if (var != null) {
              hv.add(var);
              hval.add(1.0);
            }
          }
        }
      }
      // OR-Tools 9.14 crashes natively in Highs::setSolution when given a hint, so HiGHS starts cold.
      if (!hv.isEmpty() && !"HIGHS".equals(backend)) {
        s.setHint(hv.toArray(new MPVariable[0]), hval.stream().mapToDouble(Double::doubleValue).toArray());
      }
      MPSolver.ResultStatus status = s.solve();
      if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
        if (status == MPSolver.ResultStatus.NOT_SOLVED) {
          stoppedByBudget = true;
          lastTimedOut = true;
        }
        s.delete();
        return Optional.empty();
      }
      if (cost && status != MPSolver.ResultStatus.OPTIMAL) {
        lastTimedOut = true;
      }
      if (cost) {
        lastCost = s.objective().value();
        lastBound = s.objective().bestBound();
      }
      Map<String, VehicleDay> plan = new TreeMap<>();
      for (int vi = 0; vi < vehicles.size(); vi++) {
        List<List<PlanOrder>> trips = new ArrayList<>();
        for (int t = 0; t < slots; t++) {
          trips.add(new ArrayList<>());
        }
        plan.put(vehicles.get(vi).vehicleId(), null);
        for (Map.Entry<Key, MPVariable> e : x.entrySet()) {
          if (e.getKey().vehicle() == vi && e.getValue().solutionValue() > 0.5) {
            trips.get(e.getKey().slot()).add(orders.get(e.getKey().order()));
          }
        }
        lastSlots.put(vehicles.get(vi).vehicleId(), trips);
        List<Trip> built = new ArrayList<>();
        for (List<PlanOrder> t : trips) {
          if (!t.isEmpty()) {
            Trip trip = Trip.of(t.get(0));
            for (int j = 1; j < t.size(); j++) {
              trip = trip.with(t.get(j));
            }
            built.add(trip);
          }
        }
        plan.put(vehicles.get(vi).vehicleId(), new VehicleDay(vehicles.get(vi), built));
      }
      s.delete();
      return Optional.of(plan);
    }

    /** Cuts off every vehicle-day the registry refuses; false when the plan passes. */
    private boolean addCuts(Map<String, VehicleDay> plan) {
      boolean any = false;
      for (int vi = 0; vi < vehicles.size(); vi++) {
        FleetVehicle v = vehicles.get(vi);
        VehicleDay day = plan.get(v.vehicleId());
        if (day == null || day.trips().isEmpty() || Bench.feasible(day, ctx)) {
          continue;
        }
        any = true;
        if (Boolean.getBoolean("bench.debug")) {
          Bench.REGISTRY.evaluate(new Candidate(day, ctx, Set.of())).stream().filter(r -> !r.passed())
              .forEach(r -> System.out.println("  cut " + v.vehicleId() + " " + r.ruleId() + " " + r.reason()));
        }
        // The trips in the slots the model put them in, before VehicleDay reordered them.
        List<List<PlanOrder>> bySlot = lastSlots.get(v.vehicleId());
        boolean single = false;
        for (List<PlanOrder> trip : bySlot) {
          if (trip.isEmpty()) {
            continue;
          }
          VehicleDay alone = new VehicleDay(v, List.of(tripOf(trip)));
          List<ConstraintResult> results = Bench.REGISTRY.evaluate(new Candidate(alone, ctx, Set.of()));
          if (!com.waypoint.dispatch.planning.domain.ConstraintRegistry.allPass(results)) {
            single = true;
            boolean window = results.stream().filter(r -> !r.passed()).allMatch(r -> WINDOW_RULES.contains(r.ruleId()));
            List<PlanOrder> minimal = shrink(trip, rest -> !Bench.feasible(new VehicleDay(v, List.of(tripOf(rest))), ctx));
            List<int[]> placements = new ArrayList<>();
            minimal.forEach(o -> placements.add(new int[] {index.get(o.orderId()), 0}));
            if (window) {
              // A late stop stays late on any vehicle and any later departure.
              cutList.add(new Cut(-1, placements));
            } else {
              for (int t = 0; t < slots; t++) {
                List<int[]> onSlot = new ArrayList<>();
                for (int[] pl : placements) {
                  onSlot.add(new int[] {pl[0], t});
                }
                cutList.add(new Cut(vi, onSlot));
              }
            }
            cuts++;
          }
        }
        if (!single) {
          List<int[]> placements = new ArrayList<>();
          for (int t = 0; t < bySlot.size(); t++) {
            for (PlanOrder o : bySlot.get(t)) {
              placements.add(new int[] {index.get(o.orderId()), t});
            }
          }
          cutList.add(new Cut(vi, placements));
          cuts++;
        }
      }
      return any;
    }
  }

  private static Trip tripOf(List<PlanOrder> orders) {
    Trip t = Trip.of(orders.get(0));
    for (int i = 1; i < orders.size(); i++) {
      t = t.with(orders.get(i));
    }
    return t;
  }

  /** The smallest subset that still fails, one order dropped at a time (a deletion filter). */
  private static List<PlanOrder> shrink(List<PlanOrder> orders, java.util.function.Predicate<List<PlanOrder>> fails) {
    List<PlanOrder> kept = new ArrayList<>(orders);
    for (PlanOrder o : orders) {
      if (kept.size() == 1) {
        break;
      }
      List<PlanOrder> without = new ArrayList<>(kept);
      without.remove(o);
      if (fails.test(without)) {
        kept = without;
      }
    }
    return kept;
  }
}
