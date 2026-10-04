package com.waypoint.dispatch.planning.bench;

import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.PeakDayScenario;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The benchmark's problems: the official peak day, the eight historic
 * depot-days replayed with their fleet thinned, and seeded synthetic days drawn
 * from the supplied outlets, fleet, travel and order sizes. Every one is built
 * the way {@link PeakDayScenario} builds S1, so every engine sees the same rules.
 */
final class Scenarios {
  static final Path DATA = Path.of("..", "data");
  static final RuleSet RULES = new RuleSet(UUID.nameUUIDFromBytes("rules-v1".getBytes()), RuleSet.bookletParameters());
  static final PriorityPolicy POLICY =
      new PriorityPolicy(UUID.nameUUIDFromBytes("policy-v1".getBytes()), PriorityPolicy.DEFAULT_KEYS);

  record Scenario(String name, String family, Problem problem) {}

  private Scenarios() {}

  static Scenario s1() {
    return new Scenario("S1", "official", PeakDayScenario.load(DATA, RULES, POLICY).problem());
  }

  // ---- reference data -------------------------------------------------------

  record Outlet(String id, String brand, String district, String depot, String dock, String parking, String mall,
      String open, String close) {}

  record Size(String brand, String temp, BigDecimal kg, BigDecimal m3) {}

  static final class Reference {
    final Map<String, Integer> allowance = new HashMap<>();
    final Map<String, Map<String, DistrictTravel>> travelByDepot = new HashMap<>();
    final Map<String, Outlet> outlets = new TreeMap<>();
    final List<Map<String, String>> vehicles;
    final List<Size> sizes = new ArrayList<>();
    final List<Map<String, String>> deliveries;

    Reference() {
      Path g = DATA.resolve("General Data");
      for (Map<String, String> r : read(g.resolve("service_allowance.csv"))) {
        allowance.put(r.get("brand") + "/" + r.get("dock_type"), Integer.parseInt(r.get("service_allowance_min")));
      }
      for (Map<String, String> r : read(g.resolve("district_travel.csv"))) {
        travelByDepot.computeIfAbsent(r.get("depot"), k -> new HashMap<>()).put(r.get("district"),
            new DistrictTravel(r.get("district"), new BigDecimal(r.get("depot_to_district_freeflow_min")),
                new BigDecimal(r.get("inter_stop_freeflow_min")), new BigDecimal(r.get("depot_to_district_km")),
                new BigDecimal(r.get("inter_stop_km"))));
      }
      for (Map<String, String> r : read(g.resolve("outlets.csv"))) {
        outlets.put(r.get("outlet_id"), new Outlet(r.get("outlet_id"), r.get("brand"), r.get("district"), r.get("depot"),
            r.get("dock_type"), r.get("parking_constraint"), r.get("mall_window"), r.get("window_open_time"),
            r.get("window_close_time")));
      }
      vehicles = read(g.resolve("vehicles.csv"));
      deliveries = read(DATA.resolve("Training Data").resolve("deliveries_train.csv"));
      for (Map<String, String> r : deliveries) {
        sizes.add(new Size(r.get("brand"), r.get("temp_requirement"), new BigDecimal(r.get("order_weight_kg")),
            new BigDecimal(r.get("order_volume_m3"))));
      }
    }

    /** The depot's fleet; {@code out} vehicles in the workshop, reefers and others thinned alike. */
    List<FleetVehicle> fleet(String depot, double out, double fuelUsedShare, long seed) {
      List<Map<String, String>> rows =
          vehicles.stream().filter(r -> r.get("depot").equals(depot))
              .sorted(Comparator.comparing(r -> r.get("vehicle_id"))).toList();
      List<String> reefers = new ArrayList<>(rows.stream().filter(r -> "reefer".equals(r.get("temp"))).map(r -> r.get("vehicle_id")).toList());
      List<String> others = new ArrayList<>(rows.stream().filter(r -> !"reefer".equals(r.get("temp"))).map(r -> r.get("vehicle_id")).toList());
      Random random = new Random(seed);
      Collections.shuffle(reefers, random);
      Collections.shuffle(others, random);
      java.util.Set<String> workshop = new java.util.HashSet<>();
      workshop.addAll(reefers.subList(0, Math.min(reefers.size() - 1, (int) Math.round(reefers.size() * out))));
      workshop.addAll(others.subList(0, (int) Math.round(others.size() * out)));
      List<FleetVehicle> fleet = new ArrayList<>();
      for (Map<String, String> r : rows) {
        BigDecimal quota = new BigDecimal(r.get("weekly_fuel_quota_l"));
        fleet.add(new FleetVehicle(r.get("vehicle_id"), depot, "van".equals(r.get("type")), "reefer".equals(r.get("temp")),
            new BigDecimal(r.get("weight_cap_kg")), new BigDecimal(r.get("volume_cap_m3")), new BigDecimal(r.get("km_per_l")),
            quota, !workshop.contains(r.get("vehicle_id")),
            quota.multiply(BigDecimal.valueOf(fuelUsedShare)).setScale(1, RoundingMode.HALF_UP)));
      }
      return fleet;
    }

    PlanOrder order(String scenario, int i, Outlet o, String temp, BigDecimal kg, BigDecimal m3, int deferrals,
        int daysSince, LocalDate date) {
      String ref = scenario + "-" + String.format("%03d", i);
      LocalTime open = LocalTime.parse(o.open());
      LocalTime close = LocalTime.parse(o.close());
      Optional<LocalTime> wo = Optional.of(open);
      Optional<LocalTime> wc = Optional.of(close);
      if (o.mall() != null && !o.mall().isBlank()) {
        String[] p = o.mall().split("-");
        LocalTime mo = LocalTime.parse(p[0].trim());
        LocalTime mc = LocalTime.parse(p[1].trim());
        LocalTime eo = open.isAfter(mo) ? open : mo;
        LocalTime ec = close.isBefore(mc) ? close : mc;
        wo = eo.isBefore(ec) ? Optional.of(eo) : Optional.empty();
        wc = eo.isBefore(ec) ? Optional.of(ec) : Optional.empty();
      }
      return new PlanOrder(UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)), ref, o.id(), o.depot(), o.brand(),
          o.district(), temp, kg, m3, o.dock(), "van_only".equals(o.parking()), "mall_dock".equals(o.parking()), wo, wc,
          BigDecimal.valueOf(allowance.get(o.brand() + "/" + o.dock())), deferrals, daysSince, date);
    }
  }

  // ---- historic depot-days -------------------------------------------------

  /** Each depot-day of the training deliveries, as dispatched, then with 25% and 40% of the fleet out. */
  static List<Scenario> historic(Reference ref) {
    Map<String, List<Map<String, String>>> days = new TreeMap<>();
    for (Map<String, String> r : ref.deliveries) {
      days.computeIfAbsent(r.get("dispatch_date") + "|" + r.get("depot"), k -> new ArrayList<>()).add(r);
    }
    List<Scenario> out = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, String>>> e : days.entrySet()) {
      String[] key = e.getKey().split("\\|");
      LocalDate date = LocalDate.parse(key[0]);
      String depot = key[1];
      for (double cut : new double[] {0.0, 0.25, 0.40}) {
        String name = "H-" + key[0] + "-" + depot.substring(0, 3) + "-out" + Math.round(cut * 100);
        List<PlanOrder> orders = new ArrayList<>();
        int i = 0;
        for (Map<String, String> r : e.getValue()) {
          Outlet o = ref.outlets.get(r.get("outlet_id"));
          orders.add(ref.order(name, i++, o, r.get("temp_requirement"), new BigDecimal(r.get("order_weight_kg")),
              new BigDecimal(r.get("order_volume_m3")), 0, 1, date));
        }
        out.add(new Scenario(name, "historic", new Problem(depot, date, orders, ref.fleet(depot, cut, 0, name.hashCode()),
            ref.travelByDepot.get(depot), RULES, POLICY)));
      }
    }
    return out;
  }

  // ---- synthetic days ------------------------------------------------------

  record Family(String name, int orders, double chilledShare, double workshop, double fuelUsed, double sizeScale,
      double priorSkip) {}

  static final List<Family> FAMILIES = List.of(
      new Family("base-60", 60, 0.35, 0.20, 0.0, 1.0, 0.10),
      new Family("base-120", 120, 0.35, 0.20, 0.0, 1.0, 0.10),
      new Family("base-200", 200, 0.35, 0.20, 0.0, 1.0, 0.10),
      new Family("reefer-short", 90, 0.65, 0.35, 0.0, 1.0, 0.10),
      new Family("tight-fuel", 90, 0.35, 0.20, 0.85, 1.0, 0.10),
      new Family("big-orders", 90, 0.35, 0.20, 0.0, 1.8, 0.10),
      new Family("prior-skips", 90, 0.35, 0.25, 0.0, 1.0, 0.45),
      new Family("large-300", 300, 0.35, 0.20, 0.0, 1.0, 0.10));

  /**
   * One synthetic day: outlets of one depot drawn at random, a Fresh outlet's
   * order chilled with the family's share, sizes drawn from the training
   * deliveries of the same brand and temperature, then scaled.
   */
  static Scenario synthetic(Reference ref, Family f, long seed) {
    Random random = new Random(seed * 7919 + f.name().hashCode());
    String depot = random.nextBoolean() ? "Peliyagoda" : "Kandy";
    List<Outlet> outlets = ref.outlets.values().stream().filter(o -> o.depot().equals(depot)).toList();
    Map<String, List<Size>> sizes = new LinkedHashMap<>();
    ref.sizes.forEach(s -> sizes.computeIfAbsent(s.brand() + "|" + s.temp(), k -> new ArrayList<>()).add(s));
    String name = "G-" + f.name() + "-" + seed;
    LocalDate date = LocalDate.of(2026, 10, 5);
    List<PlanOrder> orders = new ArrayList<>();
    for (int i = 0; i < f.orders(); i++) {
      Outlet o = outlets.get(random.nextInt(outlets.size()));
      String temp = "Fresh".equals(o.brand()) && random.nextDouble() < f.chilledShare() ? "chilled" : "ambient";
      List<Size> pool = sizes.getOrDefault(o.brand() + "|" + temp, sizes.get(o.brand() + "|ambient"));
      Size s = pool.get(random.nextInt(pool.size()));
      BigDecimal k = BigDecimal.valueOf(f.sizeScale());
      orders.add(ref.order(name, i, o, temp, s.kg().multiply(k).setScale(1, RoundingMode.HALF_UP),
          s.m3().multiply(k).setScale(3, RoundingMode.HALF_UP), random.nextDouble() < f.priorSkip() ? 1 : 0,
          1 + random.nextInt(7), date));
    }
    return new Scenario(name, f.name(), new Problem(depot, date, orders,
        ref.fleet(depot, f.workshop(), f.fuelUsed(), seed), ref.travelByDepot.get(depot), RULES, POLICY));
  }

  static List<Map<String, String>> read(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      String[] header = lines.get(0).replace("﻿", "").split(",", -1);
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
