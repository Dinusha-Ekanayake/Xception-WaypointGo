package com.waypoint.dispatch.planning.infrastructure;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * R-PLN-21: replays deferral priority orders over many stressed days, so a
 * change to the default order rests on more than one scenario. Each day keeps
 * its real orders, outlets, windows and history ({@code deferred_yesterday}
 * and days since last served, from the dispatch record); its fleet loses a
 * seeded share of vehicles to the workshop, as on the Task 2B peak day. Runs
 * only with {@code WAYPOINT_DATATHON_DIR} pointing at the Datathon data, which
 * stays on the machine that holds it. Prints one summary line per order and
 * writes the per-day rows to {@code target/priority-replay.csv}.
 */
class PriorityReplayTest {
  static final int DAYS_PER_DEPOT = 40;
  static final double REEFERS_OUT = 0.55;
  static final double DRY_OUT = 0.20;

  record Stats(int served, double chilledM3, int skippedTwice, int deferredLongWait, int maxWaitDeferred,
      int deferred) {}

  @Test
  void deferralOrdersAreComparedOverStressedDays() throws Exception {
    String dir = System.getenv("WAYPOINT_DATATHON_DIR");
    assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "set WAYPOINT_DATATHON_DIR to run the replay");
    Path data = Path.of(dir);
    Path general = PeakDayAllocationTest.DATA.resolve("General Data");

    Map<String, Integer> allowance = new HashMap<>();
    for (var r : PeakDayScenario.read(general.resolve("service_allowance.csv"))) {
      allowance.put(r.get("brand") + "/" + r.get("dock_type"), Integer.parseInt(r.get("service_allowance_min")));
    }
    Map<String, DistrictTravel> travel = new HashMap<>();
    for (var r : PeakDayScenario.read(general.resolve("district_travel.csv"))) {
      travel.put(r.get("district"), new DistrictTravel(r.get("district"),
          new BigDecimal(r.get("depot_to_district_freeflow_min")), new BigDecimal(r.get("inter_stop_freeflow_min")),
          new BigDecimal(r.get("depot_to_district_km")), new BigDecimal(r.get("inter_stop_km"))));
    }
    Map<String, Map<String, String>> outlets = new HashMap<>();
    for (var r : PeakDayScenario.read(general.resolve("outlets.csv"))) {
      outlets.put(r.get("outlet_id"), r);
    }
    List<Map<String, String>> vehicles = PeakDayScenario.read(general.resolve("vehicles.csv"));

    // The dispatch history: orders by depot and day, and each outlet's served and deferred days.
    Map<String, TreeMap<LocalDate, List<Map<String, String>>>> days = new HashMap<>();
    Map<String, java.util.TreeSet<LocalDate>> served = new HashMap<>();
    Map<String, java.util.Set<LocalDate>> deferred = new HashMap<>();
    for (var r : PeakDayScenario.read(data.resolve("Training Data").resolve("deliveries_train.csv"))) {
      String date = r.get("dispatch_date");
      if (date == null || date.isBlank()) {
        continue;
      }
      LocalDate day = LocalDate.parse(date);
      days.computeIfAbsent(r.get("depot"), k -> new TreeMap<>()).computeIfAbsent(day, k -> new ArrayList<>()).add(r);
      if ("attempted".equals(r.get("dispatch_status"))) {
        served.computeIfAbsent(r.get("outlet_id"), k -> new java.util.TreeSet<>()).add(day);
      } else if ("deferred".equals(r.get("dispatch_status"))) {
        deferred.computeIfAbsent(r.get("outlet_id"), k -> new java.util.HashSet<>()).add(day);
      }
    }

    Map<String, List<PriorityPolicy.Key>> policies = new java.util.LinkedHashMap<>();
    // Version 1, the order before this replay, and version 2 (the default), which it led to (R-PLN-21).
    policies.put("version-1", List.of(
        PriorityPolicy.Key.PRIOR_SKIP, PriorityPolicy.Key.FRESH, PriorityPolicy.Key.CHILLED,
        PriorityPolicy.Key.STRICT_WINDOW, PriorityPolicy.Key.CADENCE, PriorityPolicy.Key.EARLIEST_CLOSE,
        PriorityPolicy.Key.LONGEST_DISTANCE, PriorityPolicy.Key.LARGEST_VOLUME, PriorityPolicy.Key.LONGEST_UNSERVED));
    policies.put("version-2", PriorityPolicy.DEFAULT_KEYS);
    policies.put("unserved-second", move(PriorityPolicy.Key.PRIOR_SKIP));
    // R-PLN-47: version 2 again, under the depot's clock (the drive back and a 20 minute reload before a second trip).
    policies.put("version-2-depot-clock", PriorityPolicy.DEFAULT_KEYS);
    com.waypoint.dispatch.planning.domain.RuleSet depotRules = new com.waypoint.dispatch.planning.domain.RuleSet(
        UUID.nameUUIDFromBytes("rules-depot".getBytes()), com.waypoint.dispatch.planning.domain.RuleSet.depotParameters());

    StringBuilder rows = new StringBuilder("policy,depot,day,orders,served,chilled_m3,skipped_twice,deferred_waited_2plus,max_wait_deferred\n");
    Map<String, double[]> totals = new java.util.LinkedHashMap<>();
    for (String depot : List.of("Peliyagoda", "Kandy")) {
      TreeMap<LocalDate, List<Map<String, String>>> history = days.get(depot);
      // The busiest days, where capacity is most likely to run short.
      List<LocalDate> busiest = history.entrySet().stream()
          .filter(e -> e.getKey().isAfter(history.firstKey().plusDays(7)))
          .sorted(Comparator.comparingDouble((Map.Entry<LocalDate, List<Map<String, String>>> e) -> -e.getValue().stream()
              .mapToDouble(r -> Double.parseDouble(r.get("order_volume_m3"))).sum()))
          .limit(DAYS_PER_DEPOT).map(Map.Entry::getKey).sorted().toList();
      for (LocalDate day : busiest) {
        List<PlanOrder> orders = new ArrayList<>();
        for (var r : history.get(day)) {
          var o = outlets.get(r.get("outlet_id"));
          var w = PeakDayScenario.effectiveWindow(r.get("window_open_time"), r.get("window_close_time"), o.get("mall_window"));
          LocalDate last = Optional.ofNullable(served.get(r.get("outlet_id"))).map(s -> s.lower(day)).orElse(day.minusDays(7));
          boolean skipped = deferred.getOrDefault(r.get("outlet_id"), java.util.Set.of()).contains(day.minusDays(1));
          orders.add(new PlanOrder(UUID.nameUUIDFromBytes(r.get("delivery_id").getBytes(StandardCharsets.UTF_8)),
              r.get("delivery_id"), r.get("outlet_id"), depot, r.get("brand"), r.get("district"), r.get("temp_requirement"),
              new BigDecimal(r.get("order_weight_kg")), new BigDecimal(r.get("order_volume_m3")), o.get("dock_type"),
              "van_only".equals(o.get("parking_constraint")), "mall_dock".equals(o.get("parking_constraint")),
              w.open(), w.close(), BigDecimal.valueOf(allowance.get(r.get("brand") + "/" + o.get("dock_type"))),
              skipped ? 1 : 0, (int) Math.min(7, ChronoUnit.DAYS.between(last, day)), day, Optional.empty()));
        }
        Random random = new Random(day.toEpochDay() * 31 + depot.hashCode());
        List<FleetVehicle> fleet = new ArrayList<>();
        for (var v : vehicles) {
          if (!v.get("depot").equals(depot)) {
            continue;
          }
          boolean reefer = "reefer".equals(v.get("temp"));
          boolean out = random.nextDouble() < (reefer ? REEFERS_OUT : DRY_OUT);
          fleet.add(new FleetVehicle(v.get("vehicle_id"), depot, "van".equals(v.get("type")), reefer,
              new BigDecimal(v.get("weight_cap_kg")), new BigDecimal(v.get("volume_cap_m3")), new BigDecimal(v.get("km_per_l")),
              new BigDecimal(v.get("weekly_fuel_quota_l")), !out, BigDecimal.ZERO));
        }
        for (var p : policies.entrySet()) {
          PriorityPolicy policy = new PriorityPolicy(UUID.nameUUIDFromBytes(p.getKey().getBytes()), p.getValue());
          Problem problem = new Problem(depot, day, orders, fleet, travel,
              p.getKey().endsWith("depot-clock") ? depotRules : PeakDayAllocationTest.RULES, policy);
          AllocationResult r = PeakDayAllocationTest.engine().allocate(problem);
          Stats s = stats(orders, r);
          rows.append(String.join(",", p.getKey(), depot, day.toString(), String.valueOf(orders.size()),
              String.valueOf(s.served()), String.format("%.3f", s.chilledM3()), String.valueOf(s.skippedTwice()),
              String.valueOf(s.deferredLongWait()), String.valueOf(s.maxWaitDeferred()))).append('\n');
          double[] t = totals.computeIfAbsent(p.getKey(), k -> new double[6]);
          t[0] += s.served();
          t[1] += s.chilledM3();
          t[2] += s.skippedTwice();
          t[3] += s.deferredLongWait();
          t[4] = Math.max(t[4], s.maxWaitDeferred());
          t[5] += s.deferred();
        }
      }
    }
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target", "priority-replay.csv"), rows.toString(), StandardCharsets.UTF_8);
    totals.forEach((name, t) -> System.out.printf(
        "Replay %s: served %d, deferred %d, chilled %.1f m3, skipped twice %d, deferred after waiting 2+ days %d, longest wait deferred %d%n",
        name, (int) t[0], (int) t[5], t[1], (int) t[2], (int) t[3], (int) t[4]));
  }

  /** The default order with the longest-unserved key moved to just after {@code after}. */
  private static List<PriorityPolicy.Key> move(PriorityPolicy.Key after) {
    List<PriorityPolicy.Key> keys = new ArrayList<>(PriorityPolicy.DEFAULT_KEYS);
    keys.remove(PriorityPolicy.Key.LONGEST_UNSERVED);
    keys.add(keys.indexOf(after) + 1, PriorityPolicy.Key.LONGEST_UNSERVED);
    return keys;
  }

  private static Stats stats(List<PlanOrder> orders, AllocationResult r) {
    int served = 0;
    int skippedTwice = 0;
    int longWait = 0;
    int maxWait = 0;
    int deferredCount = 0;
    double chilled = 0;
    for (PlanOrder o : orders) {
      boolean ok = r.decisionFor(o.orderId()).orElseThrow().decision() == AllocationDecision.SERVED;
      if (ok) {
        served++;
        if ("chilled".equals(o.temperature())) {
          chilled += o.volumeM3().doubleValue();
        }
      } else {
        deferredCount++;
        if (o.deferralCount() > 0) {
          skippedTwice++;
        }
        if (o.daysSinceServed() >= 2) {
          longWait++;
        }
        maxWait = Math.max(maxWait, o.daysSinceServed());
      }
    }
    return new Stats(served, chilled, skippedTwice, longWait, maxWait, deferredCount);
  }
}
