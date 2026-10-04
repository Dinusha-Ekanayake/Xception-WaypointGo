package com.waypoint.dispatch.planning.bench;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.IntVar;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.Solver;
import com.google.ortools.constraintsolver.main;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.TemperatureClass;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import com.waypoint.dispatch.planning.infrastructure.PriorityInsertionEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * E4: Google OR-Tools vehicle routing, as a routing team would model this day.
 * Each (vehicle, trip slot) is a routing vehicle. Temperature and van-only
 * access are allowed-vehicle lists; weight and volume are capacity dimensions;
 * Fresh and daytime minutes are dimensions summed over a vehicle's two slots
 * (270/480); fuel is a kilometre dimension against the weekly quota; a clock
 * dimension carries delivery windows and makes the second trip leave after the
 * first. One brand and district per trip is a pairwise constraint. An order
 * may be dropped at a penalty that falls with its rank (R-PLN-21 as weights).
 * Whatever the production registry still refuses is removed afterwards and
 * counted, so the plan stays valid.
 */
final class RoutingEngine implements AllocationEngine, EngineBenchmark.Extra {
  private static final long SCALE = 10;
  private final long budgetMs;
  private int repaired;
  private String status = "";

  RoutingEngine(long budgetMs) {
    Loader.loadNativeLibraries();
    this.budgetMs = budgetMs;
  }

  @Override
  public String name() {
    return "E4-routing";
  }

  @Override
  public String extra() {
    return "repaired=" + repaired + " " + status;
  }

  @Override
  public AllocationResult allocate(Problem p) {
    PlanContext ctx = p.context();
    AllocationResult screen = new PriorityInsertionEngine(Bench.REGISTRY).allocate(p);
    List<OrderDecision> unservable = screen.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.UNSERVABLE).toList();
    Set<UUID> skip = new HashSet<>();
    unservable.forEach(d -> skip.add(d.orderId()));
    List<PlanOrder> orders = Bench.ranked(p, skip);
    List<FleetVehicle> fleet = p.fleet().stream().filter(FleetVehicle::available)
        .sorted(java.util.Comparator.comparing(FleetVehicle::vehicleId)).toList();
    int slots = p.rules().maxTrips();
    int nodes = orders.size() + 1;
    int routes = fleet.size() * slots;

    RoutingIndexManager manager = new RoutingIndexManager(nodes, routes, 0);
    RoutingModel routing = new RoutingModel(manager);
    Solver solver = routing.solver();

    // Kilometres: out to the district, between stops, and home again (fuel counts the return, R-PLN-24).
    final int kmCallback = routing.registerTransitCallback((long from, long to) -> {
      int i = manager.indexToNode(from);
      int j = manager.indexToNode(to);
      if (i == 0 && j == 0) {
        return 0;
      }
      if (i == 0) {
        return km(travel(p, orders.get(j - 1)).outboundKm().doubleValue());
      }
      if (j == 0) {
        return km(travel(p, orders.get(i - 1)).outboundKm().doubleValue());
      }
      PlanOrder a = orders.get(i - 1);
      PlanOrder b = orders.get(j - 1);
      return a.district().equals(b.district()) ? km(travel(p, b).interStopKm().doubleValue()) : 1_000_000;
    });
    routing.setArcCostEvaluatorOfAllVehicles(kmCallback);
    routing.addDimension(kmCallback, 0, 10_000_000, true, "km");
    RoutingDimension kmDim = routing.getMutableDimension("km");

    long[] weightCaps = new long[routes];
    long[] volumeCaps = new long[routes];
    for (int r = 0; r < routes; r++) {
      FleetVehicle v = fleet.get(r / slots);
      weightCaps[r] = (long) Math.floor(v.weightCapKg().doubleValue() * SCALE);
      volumeCaps[r] = (long) Math.floor(v.volumeCapM3().doubleValue() * 1000);
    }
    int weight = routing.registerUnaryTransitCallback((long from) -> {
      int i = manager.indexToNode(from);
      return i == 0 ? 0 : (long) Math.ceil(orders.get(i - 1).weightKg().doubleValue() * SCALE);
    });
    routing.addDimensionWithVehicleCapacity(weight, 0, weightCaps, true, "weight");
    int volume = routing.registerUnaryTransitCallback((long from) -> {
      int i = manager.indexToNode(from);
      return i == 0 ? 0 : (long) Math.ceil(orders.get(i - 1).volumeM3().doubleValue() * 1000);
    });
    routing.addDimensionWithVehicleCapacity(volume, 0, volumeCaps, true, "volume");

    // Booklet minutes, split by kind: Fresh trips count to 270, the rest to 480.
    int freshMinutes = routing.registerTransitCallback((long from, long to) -> formulaStep(p, orders, manager, from, to, true));
    routing.addDimension(freshMinutes, 0, p.rules().freshBudgetMinutes().longValue() * SCALE, true, "fresh");
    int dayMinutes = routing.registerTransitCallback((long from, long to) -> formulaStep(p, orders, manager, from, to, false));
    routing.addDimension(dayMinutes, 0, p.rules().daytimeBudgetMinutes().longValue() * SCALE, true, "day");
    RoutingDimension fresh = routing.getMutableDimension("fresh");
    RoutingDimension day = routing.getMutableDimension("day");

    // The clock: service at the stop left, then the drive; waiting allowed; windows on arrival.
    int clock = routing.registerTransitCallback((long from, long to) -> {
      int i = manager.indexToNode(from);
      int j = manager.indexToNode(to);
      long service = i == 0 ? 0 : (long) (orders.get(i - 1).serviceMinutes().doubleValue() * SCALE);
      if (j == 0) {
        return service;
      }
      DistrictTravel d = travel(p, orders.get(j - 1));
      double drive = i == 0 ? d.outboundMinutes().doubleValue() : d.interStopMinutes().doubleValue();
      return service + (long) Math.ceil(drive * SCALE);
    });
    routing.addDimension(clock, 24 * 60 * SCALE, 24 * 60 * SCALE, false, "clock");
    RoutingDimension time = routing.getMutableDimension("clock");
    long freshStart = p.rules().freshDeparture().toSecondOfDay() / 60 * SCALE;
    long dayStart = p.rules().daytimeDeparture().toSecondOfDay() / 60 * SCALE;
    for (int n = 1; n < nodes; n++) {
      PlanOrder o = orders.get(n - 1);
      long index = manager.nodeToIndex(n);
      long earliest = (o.fresh() ? freshStart : dayStart)
          + (long) Math.ceil(travel(p, o).outboundMinutes().doubleValue() * SCALE);
      long close = o.windowClose().map(t -> (long) (t.toSecondOfDay() / 60) * SCALE).orElse(24 * 60 * SCALE);
      time.cumulVar(index).setRange(Math.min(earliest, close), close);
    }
    for (int r = 0; r < routes; r++) {
      time.cumulVar(routing.start(r)).setRange(freshStart, 24 * 60 * SCALE);
    }

    // Allowed vehicles, and a penalty for leaving an order out, falling with rank.
    for (int n = 1; n < nodes; n++) {
      PlanOrder o = orders.get(n - 1);
      List<Integer> allowed = new ArrayList<>();
      for (int r = 0; r < routes; r++) {
        FleetVehicle v = fleet.get(r / slots);
        if ((o.temperatureClass() != TemperatureClass.CHILLED || v.reefer()) && (!o.vanOnly() || v.van())) {
          allowed.add(r);
        }
      }
      long index = manager.nodeToIndex(n);
      routing.setAllowedVehiclesForIndex(allowed.stream().mapToInt(Integer::intValue).toArray(), index);
      long penalty = 100_000L * (orders.size() - (n - 1)) + 1_000_000L;
      routing.addDisjunction(new long[] {index}, penalty);
    }

    // One brand, district and temperature per trip.
    for (int a = 1; a < nodes; a++) {
      for (int b = a + 1; b < nodes; b++) {
        PlanOrder oa = orders.get(a - 1);
        PlanOrder ob = orders.get(b - 1);
        if (oa.brand().equals(ob.brand()) && oa.district().equals(ob.district())
            && oa.temperatureClass() == ob.temperatureClass()) {
          continue;
        }
        long ia = manager.nodeToIndex(a);
        long ib = manager.nodeToIndex(b);
        IntVar differ = solver.makeIsDifferentCstVar(routing.vehicleVar(ia), routing.vehicleVar(ib));
        solver.addConstraint(solver.makeLessOrEqual(
            solver.makeSum(routing.activeVar(ia), routing.activeVar(ib)),
            solver.makeSum(differ, 1)));
      }
    }

    // A vehicle's two slots: budgets and fuel shared, the second trip after the first.
    for (int v = 0; v < fleet.size(); v++) {
      FleetVehicle vehicle = fleet.get(v);
      int r0 = v * slots;
      int r1 = r0 + 1;
      if (slots < 2) {
        continue;
      }
      solver.addConstraint(solver.makeLessOrEqual(
          solver.makeSum(fresh.cumulVar(routing.end(r0)), fresh.cumulVar(routing.end(r1))),
          p.rules().freshBudgetMinutes().longValue() * SCALE));
      solver.addConstraint(solver.makeLessOrEqual(
          solver.makeSum(day.cumulVar(routing.end(r0)), day.cumulVar(routing.end(r1))),
          p.rules().daytimeBudgetMinutes().longValue() * SCALE));
      long kmLeft = (long) Math.floor(vehicle.weeklyFuelQuotaL().subtract(vehicle.fuelUsedThisWeekL()).doubleValue()
          * vehicle.kmPerL().doubleValue() * SCALE);
      solver.addConstraint(solver.makeLessOrEqual(
          solver.makeSum(kmDim.cumulVar(routing.end(r0)), kmDim.cumulVar(routing.end(r1))), Math.max(0, kmLeft)));
      solver.addConstraint(solver.makeGreaterOrEqual(time.cumulVar(routing.start(r1)), time.cumulVar(routing.end(r0))));
    }

    RoutingSearchParameters params = main.defaultRoutingSearchParameters().toBuilder()
        .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
        .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
        .setTimeLimit(com.google.protobuf.Duration.newBuilder().setSeconds(budgetMs / 1000).build())
        .build();
    Assignment solution = routing.solveWithParameters(params);
    status = "status=" + routing.status();

    // Read the routes back as vehicle days, then remove whatever the registry still refuses.
    Map<String, List<Trip>> trips = new TreeMap<>();
    if (solution != null) {
      for (int r = 0; r < routes; r++) {
        List<PlanOrder> stops = new ArrayList<>();
        long index = routing.start(r);
        while (!routing.isEnd(index)) {
          int node = manager.indexToNode(index);
          if (node > 0) {
            stops.add(orders.get(node - 1));
          }
          index = solution.value(routing.nextVar(index));
        }
        if (!stops.isEmpty()) {
          Trip t = Trip.of(stops.get(0));
          for (int k = 1; k < stops.size(); k++) {
            t = t.with(stops.get(k));
          }
          trips.computeIfAbsent(fleet.get(r / slots).vehicleId(), k -> new ArrayList<>()).add(t);
        }
      }
    }
    repaired = 0;
    List<VehicleDay> days = new ArrayList<>();
    for (FleetVehicle v : fleet) {
      VehicleDay d = new VehicleDay(v, trips.getOrDefault(v.vehicleId(), List.of()));
      // Lowest ranked out first until the registry accepts the day.
      List<PlanOrder> carried = new ArrayList<>(d.trips().stream().flatMap(t -> t.orders().stream()).toList());
      carried.sort(java.util.Comparator.comparingInt(orders::indexOf).reversed());
      for (PlanOrder o : carried) {
        if (Bench.feasible(d, ctx)) {
          break;
        }
        d = d.without(o.orderId());
        repaired++;
      }
      days.add(d);
    }
    return Bench.finish(p, days, unservable, name());
  }

  private static long km(double km) {
    return Math.round(km * SCALE);
  }

  private static DistrictTravel travel(Problem p, PlanOrder o) {
    return p.travel().get(o.district());
  }

  /** The booklet formula, one step: outbound to the first stop, inter-stop after, plus the stop's allowance. */
  private static long formulaStep(Problem p, List<PlanOrder> orders, RoutingIndexManager manager, long from, long to,
      boolean freshKind) {
    int i = manager.indexToNode(from);
    int j = manager.indexToNode(to);
    if (j == 0) {
      return 0;
    }
    PlanOrder o = orders.get(j - 1);
    if (o.fresh() != freshKind) {
      return 0;
    }
    DistrictTravel d = travel(p, o);
    double drive = i == 0 ? d.outboundMinutes().doubleValue() : d.interStopMinutes().doubleValue();
    return (long) Math.ceil((drive + o.serviceMinutes().doubleValue()) * SCALE);
  }
}
