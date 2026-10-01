package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Task 2B peak day (scenario S1) as a planning problem, read straight from
 * the supplied CSVs, and the engine's answer written in the submission format
 * that {@code tools/check_allocation/check_allocation.py} judges.
 *
 * <p>The scenario carries no fuel history, so every vehicle starts the week
 * unused. Order ids are derived from {@code order_ref}, so reruns are identical.
 */
public final class PeakDayScenario {
  public static final String SCENARIO = "S1";
  public static final LocalDate SERVICE_DATE = LocalDate.of(2026, 10, 5);

  private final Map<UUID, String> refs = new LinkedHashMap<>();
  private final Problem problem;

  private PeakDayScenario(Problem problem) {
    this.problem = problem;
  }

  public Problem problem() {
    return problem;
  }

  public static PeakDayScenario load(Path dataDir, RuleSet rules, PriorityPolicy policy) {
    Path general = dataDir.resolve("General Data");
    Path test = dataDir.resolve("Test Data");

    Map<String, Integer> allowance = new HashMap<>();
    for (Map<String, String> r : read(general.resolve("service_allowance.csv"))) {
      allowance.put(r.get("brand") + "/" + r.get("dock_type"), Integer.parseInt(r.get("service_allowance_min")));
    }
    Map<String, DistrictTravel> travel = new HashMap<>();
    for (Map<String, String> r : read(general.resolve("district_travel.csv"))) {
      travel.put(
          r.get("district"),
          new DistrictTravel(
              r.get("district"),
              new BigDecimal(r.get("depot_to_district_freeflow_min")),
              new BigDecimal(r.get("inter_stop_freeflow_min")),
              new BigDecimal(r.get("depot_to_district_km")),
              new BigDecimal(r.get("inter_stop_km"))));
    }
    Map<String, String> status = new HashMap<>();
    for (Map<String, String> r : read(test.resolve("task2b_peak_day_fleet.csv"))) {
      status.put(r.get("vehicle_id"), r.get("status"));
    }

    List<PlanOrder> orders = new ArrayList<>();
    String depot = null;
    PeakDayScenario scenario;
    Map<UUID, String> refs = new LinkedHashMap<>();
    for (Map<String, String> r : read(test.resolve("task2b_peak_day_scenarios.csv"))) {
      depot = r.get("depot");
      UUID id = UUID.nameUUIDFromBytes((SCENARIO + "/" + r.get("order_ref")).getBytes(StandardCharsets.UTF_8));
      refs.put(id, r.get("order_ref"));
      Window w = effectiveWindow(r.get("window_open_time"), r.get("window_close_time"), r.get("mall_window"));
      boolean deferredYesterday = "1".equals(r.get("deferred_yesterday"));
      orders.add(
          new PlanOrder(
              id,
              r.get("order_ref"),
              r.get("outlet_id"),
              depot,
              r.get("brand"),
              r.get("district"),
              r.get("temp_requirement"),
              new BigDecimal(r.get("order_weight_kg")),
              new BigDecimal(r.get("order_volume_m3")),
              r.get("dock_type"),
              "van_only".equals(r.get("parking_constraint")),
              "mall_dock".equals(r.get("parking_constraint")),
              w.open(),
              w.close(),
              BigDecimal.valueOf(allowance.get(r.get("brand") + "/" + r.get("dock_type"))),
              deferredYesterday ? 1 : 0,
              Integer.parseInt(r.get("days_since_last_served")),
              SERVICE_DATE));
    }

    List<FleetVehicle> fleet = new ArrayList<>();
    for (Map<String, String> r : read(general.resolve("vehicles.csv"))) {
      if (!r.get("depot").equals(depot)) {
        continue;
      }
      fleet.add(
          new FleetVehicle(
              r.get("vehicle_id"),
              r.get("depot"),
              "van".equals(r.get("type")),
              "reefer".equals(r.get("temp")),
              new BigDecimal(r.get("weight_cap_kg")),
              new BigDecimal(r.get("volume_cap_m3")),
              new BigDecimal(r.get("km_per_l")),
              new BigDecimal(r.get("weekly_fuel_quota_l")),
              "available".equals(status.get(r.get("vehicle_id"))),
              BigDecimal.ZERO));
    }

    scenario =
        new PeakDayScenario(new Problem(depot, SERVICE_DATE, orders, fleet, travel, rules, policy));
    scenario.refs.putAll(refs);
    return scenario;
  }

  /** {@code scenario,order_ref,outlet_id,decision,vehicle_id,trip_id}; unservable is reported as deferred. */
  public String submissionCsv(AllocationResult result) {
    Map<UUID, PlanOrder> byId = new HashMap<>();
    problem.orders().forEach(o -> byId.put(o.orderId(), o));
    StringBuilder out = new StringBuilder("scenario,order_ref,outlet_id,decision,vehicle_id,trip_id\n");
    for (Map.Entry<UUID, String> e : refs.entrySet()) {
      OrderDecision d = result.decisionFor(e.getKey()).orElseThrow();
      PlanOrder o = byId.get(e.getKey());
      boolean served = d.decision() == AllocationDecision.SERVED;
      out.append(SCENARIO).append(',').append(e.getValue()).append(',').append(o.outletId()).append(',')
          .append(served ? "served" : "deferred").append(',')
          .append(served ? d.vehicleId().orElseThrow() : "").append(',')
          .append(served ? d.tripNumber().orElseThrow().toString() : "").append('\n');
    }
    return out.toString();
  }

  public String orderRef(UUID orderId) {
    return refs.get(orderId);
  }

  private record Window(Optional<LocalTime> open, Optional<LocalTime> close) {}

  /** R-PLN-29: the outlet window intersected with the mall window; empty when they do not overlap. */
  static Window effectiveWindow(String open, String close, String mall) {
    LocalTime o = LocalTime.parse(open);
    LocalTime c = LocalTime.parse(close);
    if (mall != null && !mall.isBlank()) {
      String[] parts = mall.split("-");
      LocalTime mo = LocalTime.parse(parts[0].trim());
      LocalTime mc = LocalTime.parse(parts[1].trim());
      o = o.isAfter(mo) ? o : mo;
      c = c.isBefore(mc) ? c : mc;
    }
    if (!o.isBefore(c)) {
      return new Window(Optional.empty(), Optional.empty());
    }
    return new Window(Optional.of(o), Optional.of(c));
  }

  static List<Map<String, String>> read(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      String[] header = lines.get(0).replace("\uFEFF", "").split(",", -1);
      List<Map<String, String>> rows = new ArrayList<>();
      for (String line : lines.subList(1, lines.size())) {
        if (line.isBlank()) {
          continue;
        }
        String[] cells = line.split(",", -1);
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
          row.put(header[i].trim(), i < cells.length ? cells[i].trim() : "");
        }
        rows.add(row);
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
