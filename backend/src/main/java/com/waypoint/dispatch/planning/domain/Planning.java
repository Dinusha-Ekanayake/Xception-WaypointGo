package com.waypoint.dispatch.planning.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waypoint.dispatch.referencedata.domain.ReferenceData;
import com.waypoint.dispatch.shared.error.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure route-planning rules. Direct port of lib/domain.ts: same ordering,
 * same arithmetic, same error codes so plans and messages stay identical.
 */
public final class Planning {
  public static final Map<String, String> REASONS = Map.ofEntries(
      Map.entry("weight", "Weight capacity exceeded"),
      Map.entry("volume", "Volume capacity exceeded"),
      Map.entry("temperature", "Refrigerated vehicle required"),
      Map.entry("access", "Van-only outlet access"),
      Map.entry("depot", "Vehicle belongs to another depot"),
      Map.entry("window", "Delivery window cannot be met"),
      Map.entry("fuel", "Weekly fuel quota exhausted"),
      Map.entry("trip_limit", "Two daily trips already reserved"),
      Map.entry("group", "Trip must serve one brand and district"),
      Map.entry("empty", "Trip is empty"),
      Map.entry("unavailable", "Vehicle is unavailable for this run"));

  private Planning() {}

  public static int minutes(String s) {
    String[] parts = s.split(":");
    return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
  }

  public static String clock(double n) {
    int total = (int) Math.floor(n);
    return String.format("%02d:%02d", total / 60, total % 60);
  }

  public static String week(String day) {
    java.time.LocalDate d = java.time.LocalDate.parse(day);
    int dow = (d.getDayOfWeek().getValue() + 6) % 7; // Monday = 0
    return d.minusDays(dow).toString();
  }

  public static boolean operating(String day, ReferenceData ref) {
    for (ReferenceData.CalendarRow r : ref.calendar()) {
      if (r.date().equals(day)) return "1".equals(r.isOperating());
    }
    throw new DomainException(
        "Date is outside the configured operating calendar.");
  }

  public static String nextOperating(String day, ReferenceData ref) {
    java.time.LocalDate d = java.time.LocalDate.parse(day);
    for (int i = 0; i < 14; i++) {
      d = d.plusDays(1);
      if (operating(d.toString(), ref)) return d.toString();
    }
    throw new DomainException("No eligible operating day found.");
  }

  /** Next order day for a store timestamp. Cut-off 16:00 local (+05:30). */
  public static String eligibleDay(String timestamp, ReferenceData ref) {
    java.time.OffsetDateTime t;
    try {
      t = java.time.OffsetDateTime.parse(timestamp);
    } catch (Exception e) {
      t = java.time.OffsetDateTime.parse(timestamp + "+05:30");
    }
    java.time.OffsetDateTime local = t.withOffsetSameInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30));
    String first = nextOperating(local.toLocalDate().toString(), ref);
    if (local.getHour() >= 16) return nextOperating(first, ref);
    return first;
  }

  public record RouteValidation(
      List<String> errors, List<ObjectNode> stops, double fuel, double distance, double start,
      double end) {}

  private static String str(JsonNode o, String field, String fallback) {
    JsonNode v = o.get(field);
    return v != null && !v.isNull() ? v.asText() : fallback;
  }

  private static double num(JsonNode o, String field) {
    JsonNode v = o.get(field);
    return v == null || v.isNull() ? 0 : v.asDouble();
  }

  public static RouteValidation validateRoute(
      List<ObjectNode> orders,
      ReferenceData.Vehicle v,
      ReferenceData ref,
      double start,
      double fuelUsed,
      int tripsUsed,
      ObjectMapper mapper) {
    List<String> errors = new ArrayList<>();
    if (v.status() != null && !v.status().isEmpty() && !"available".equals(v.status())) {
      errors.add("unavailable");
    }
    if (orders.isEmpty()) {
      return new RouteValidation(errors.isEmpty() ? List.of("empty") : dedupe(errors), List.of(),
          0, 0, start, start);
    }
    if (tripsUsed >= 2) errors.add("trip_limit");
    double weight = orders.stream().mapToDouble(o -> num(o, "weight")).sum();
    if (weight > v.weightCapKg() + 1e-8) errors.add("weight");
    double volume = orders.stream().mapToDouble(o -> num(o, "volume")).sum();
    if (volume > v.volumeCapM3() + 1e-8) errors.add("volume");
    Set<String> groups = new LinkedHashSet<>();
    for (ObjectNode o : orders) groups.add(str(o, "brand", "") + ":" + str(o, "district", ""));
    if (groups.size() > 1) errors.add("group");
    if (orders.stream().anyMatch(o -> !str(o, "depot", "").equals(v.depot()))) errors.add("depot");
    if (orders.stream().anyMatch(o -> "chilled".equals(str(o, "temp", "")))
        && !"reefer".equals(v.temp())) {
      errors.add("temperature");
    }
    if (orders.stream().anyMatch(o -> "van_only".equals(str(o, "parking_constraint", "")))
        && !"van".equals(v.type())) {
      errors.add("access");
    }
    ReferenceData.DistrictTravel t = null;
    String district = str(orders.get(0), "district", "");
    for (ReferenceData.DistrictTravel c : ref.districtTravel()) {
      if (c.district().equals(district)) {
        t = c;
        break;
      }
    }
    if (t == null) {
      errors.add("window");
      return new RouteValidation(dedupe(errors), List.of(), 0, 0, start, start);
    }
    double outbound = t.depotToDistrictFreeflowMin() * 1.25;
    double between = t.interStopFreeflowMin() * 1.25;
    double now = start + outbound;
    List<ObjectNode> stops = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
      ObjectNode o = orders.get(i);
      if (i > 0) now += between;
      int opening = minutes(str(o, "window_open_time", "00:00"));
      int closing = minutes(str(o, "window_close_time", "23:59"));
      if ("Fresh".equals(str(o, "brand", ""))) closing = Math.min(closing, 480);
      String mall = str(o, "mall_window", "");
      if (!mall.isEmpty() && mall.contains("-")) {
        String[] ab = mall.split("-", 2);
        opening = Math.max(opening, minutes(ab[0]));
        closing = Math.min(closing, minutes(ab[1]));
      }
      double arrival = Math.max(now, opening);
      double service = 15;
      for (ReferenceData.ServiceAllowance s : ref.serviceAllowance()) {
        if (s.brand().equals(str(o, "brand", ""))
            && s.dockType().equals(str(o, "dock_type", ""))) {
          service = s.serviceAllowanceMin();
          break;
        }
      }
      boolean mallWindow = !str(o, "mall_window", "").isEmpty();
      if (arrival > closing || (mallWindow && arrival + service > closing)) errors.add("window");
      ObjectNode stop = mapper.createObjectNode();
      stop.put("order_id", str(o, "id", ""));
      stop.put("arrival", Math.round(arrival));
      stop.put("eta", clock(arrival));
      stop.put("service", service);
      stop.put("sequence", i + 1);
      stops.add(stop);
      now = arrival + service;
    }
    double distance =
        2 * t.depotToDistrictKm() + (orders.size() - 1) * t.interStopKm();
    double fuel = distance / v.kmPerL();
    if (fuel + fuelUsed > v.weeklyFuelQuotaL() + 1e-8) errors.add("fuel");
    double end = Math.ceil(now + outbound + 20);
    double fuelRounded = Math.ceil(fuel * 1e6) / 1e6;
    return new RouteValidation(dedupe(errors), stops, fuelRounded, distance, start, end);
  }

  private static List<String> dedupe(List<String> in) {
    return new ArrayList<>(new LinkedHashSet<>(in));
  }

  /**
   * Greedy allocator. Returns a plan ObjectNode {day, revision, routes, deferred,
   * published} mirroring allocate() in lib/domain.ts.
   */
  public static ObjectNode allocate(
      List<ObjectNode> orders,
      ReferenceData ref,
      String day,
      Map<String, ReferenceData.Reservation> reservations,
      ObjectMapper mapper) {
    if (!operating(day, ref)) {
      throw new DomainException("Planning is unavailable on a non-operating date.");
    }
    List<MutableRoute> routes = new ArrayList<>();
    ArrayNode deferred = mapper.createArrayNode();
    List<ReferenceData.Vehicle> vehicles = new ArrayList<>();
    for (ReferenceData.Vehicle v : ref.vehicles()) {
      if (v.status() == null || v.status().isEmpty() || "available".equals(v.status())) {
        vehicles.add(v);
      }
    }
    vehicles.sort((a, b) -> {
      // Exact port of the TS comparator: vans and reefers sort last.
      int c = Boolean.compare("van".equals(a.type()), "van".equals(b.type()));
      if (c != 0) return c;
      c = Boolean.compare("reefer".equals(a.temp()), "reefer".equals(b.temp()));
      if (c != 0) return c;
      return a.vehicleId().compareTo(b.vehicleId());
    });

    List<ObjectNode> sorted = new ArrayList<>(orders);
    sorted.sort((a, b) -> {
      int c = Integer.compare(numInt(b, "skips"), numInt(a, "skips"));
      if (c != 0) return c;
      c = Boolean.compare(!"Fresh".equals(str(a, "brand", "")), !"Fresh".equals(str(b, "brand", "")));
      // TS: Number(a.brand!=="Fresh")-Number(b.brand!=="Fresh") -> Fresh first.
      if (c != 0) return c;
      c = Boolean.compare(!"chilled".equals(str(a, "temp", "")), !"chilled".equals(str(b, "temp", "")));
      if (c != 0) return c;
      c = str(a, "window_close_time", "").compareTo(str(b, "window_close_time", ""));
      if (c != 0) return c;
      return str(a, "id", "").compareTo(str(b, "id", ""));
    });

    for (ObjectNode o : sorted) {
      boolean assigned = false;
      Set<String> failures = new LinkedHashSet<>();
      for (MutableRoute route : routes) {
        ReferenceData.Vehicle v = findVehicle(vehicles, route.vehicleId);
        if (v == null) continue;
        boolean laterExists = false;
        for (MutableRoute r : routes) {
          if (r != route && r.vehicleId.equals(v.vehicleId()) && r.start > route.start) {
            laterExists = true;
            break;
          }
        }
        if (laterExists) continue;
        List<ObjectNode> candidate = new ArrayList<>(route.orders);
        candidate.add(o);
        double fuelBase = reservationFuel(reservations, v.vehicleId());
        double otherFuel = 0;
        int otherTrips = 0;
        for (MutableRoute r : routes) {
          if (r.vehicleId.equals(v.vehicleId()) && r != route) {
            otherFuel += r.fuel;
            otherTrips++;
          }
        }
        RouteValidation result =
            validateRoute(candidate, v, ref, route.start, fuelBase + otherFuel, otherTrips, mapper);
        if (result.errors().isEmpty()) {
          route.orders = candidate;
          route.stops = result.stops();
          route.fuel = result.fuel();
          route.distance = result.distance();
          route.start = result.start();
          route.end = result.end();
          assigned = true;
          break;
        }
      }
      if (!assigned) {
        for (ReferenceData.Vehicle v : vehicles) {
          List<MutableRoute> existing = new ArrayList<>();
          for (MutableRoute r : routes) {
            if (r.vehicleId.equals(v.vehicleId())) existing.add(r);
          }
          ReferenceData.Reservation base = reservations.getOrDefault(
              v.vehicleId(), new ReferenceData.Reservation(0, 0, 210));
          double start = Math.max(210, base.end());
          for (MutableRoute r : existing) start = Math.max(start, r.end);
          double used = base.fuel();
          for (MutableRoute r : existing) used += r.fuel;
          RouteValidation result = validateRoute(
              List.of(o), v, ref, start, used, existing.size() + base.trips(), mapper);
          if (!result.errors().isEmpty()) {
            failures.addAll(result.errors());
            continue;
          }
          MutableRoute nr = new MutableRoute();
          nr.id = String.format("RUN-%03d", routes.size() + 1);
          nr.vehicleId = v.vehicleId();
          nr.orders = new ArrayList<>(List.of(o));
          nr.stops = result.stops();
          nr.fuel = result.fuel();
          nr.distance = result.distance();
          nr.start = result.start();
          nr.end = result.end();
          routes.add(nr);
          assigned = true;
          break;
        }
      }
      if (!assigned) {
        List<String> msgs = new ArrayList<>();
        for (String k : failures) {
          if ("group".equals(k) || "depot".equals(k)) continue;
          msgs.add(REASONS.getOrDefault(k, k));
        }
        ObjectNode d = mapper.createObjectNode();
        d.put("order_id", str(o, "id", ""));
        d.put("reason", "No fit in current plan: " + String.join(", ", msgs));
        d.put("next_date", nextOperating(day, ref));
        d.put("repeat", numInt(o, "skips") > 0);
        d.put("justification", "");
        deferred.add(d);
      }
    }

    ObjectNode plan = mapper.createObjectNode();
    plan.put("day", day);
    plan.put("revision", 0);
    ArrayNode routeArr = mapper.createArrayNode();
    for (MutableRoute r : routes) {
      ObjectNode rn = mapper.createObjectNode();
      rn.put("id", r.id);
      rn.put("vehicle_id", r.vehicleId);
      ArrayNode ids = mapper.createArrayNode();
      for (ObjectNode o : r.orders) ids.add(str(o, "id", ""));
      rn.set("order_ids", ids);
      rn.set("stops", mapper.valueToTree(r.stops));
      rn.put("fuel", r.fuel);
      rn.put("distance", r.distance);
      rn.put("start", r.start);
      rn.put("end", r.end);
      rn.set("errors", mapper.createArrayNode());
      routeArr.add(rn);
    }
    plan.set("routes", routeArr);
    plan.set("deferred", deferred);
    plan.put("published", false);
    return plan;
  }

  private static class MutableRoute {
    String id;
    String vehicleId;
    List<ObjectNode> orders = new ArrayList<>();
    List<ObjectNode> stops = new ArrayList<>();
    double fuel;
    double distance;
    double start;
    double end;
  }

  private static ReferenceData.Vehicle findVehicle(List<ReferenceData.Vehicle> vs, String id) {
    for (ReferenceData.Vehicle v : vs) {
      if (v.vehicleId().equals(id)) return v;
    }
    return null;
  }

  private static double reservationFuel(Map<String, ReferenceData.Reservation> r, String id) {
    ReferenceData.Reservation v = r.get(id);
    return v == null ? 0 : v.fuel();
  }

  private static int numInt(JsonNode o, String field) {
    JsonNode v = o.get(field);
    return v == null || v.isNull() ? 0 : v.asInt(0);
  }

  /** Reservations for weekly fuel pressure + already-published trips in the same week. */
  public static Map<String, ReferenceData.Reservation> openingFuel(
      ReferenceData ref, String day, boolean demo) {
    Map<String, ReferenceData.Reservation> out = new TreeMap<>();
    if (!demo || day.compareTo("2026-02-17") < 0 || day.compareTo("2026-02-22") > 0) return out;
    for (ReferenceData.Vehicle v : ref.vehicles()) {
      out.put(v.vehicleId(),
          new ReferenceData.Reservation(v.weeklyFuelQuotaL() - 2, 0, 210));
    }
    return out;
  }

  /** Peak-day fleet availability for the 2026-02-16 scenario. */
  public static ReferenceData dayReference(
      ReferenceData ref, String day, boolean demo, Map<String, String> availability) {
    if (!demo || !"2026-02-16".equals(day)) return ref;
    List<ReferenceData.Vehicle> next = new ArrayList<>();
    for (ReferenceData.Vehicle v : ref.vehicles()) {
      next.add(v.withStatus(availability.getOrDefault(v.vehicleId(), "not_in_scenario")));
    }
    return new ReferenceData(
        ref.outlets(), next, ref.calendar(), ref.districtTravel(), ref.serviceAllowance());
  }

  public static Map<String, Object> reasonMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.putAll(REASONS);
    return m;
  }
}
