package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.DepotCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The nine import checks from FOUNDATION-PLAN 1.7.
 *
 * <p>An import either publishes completely or changes nothing, so every rule
 * runs and all violations are reported together. Fixing them one failed import
 * at a time is nobody's idea of a morning.
 *
 * <p>Pure: no database, no clock, no framework. The expected counts are
 * parameters rather than constants, because a validator that hardcodes 120
 * outlets stops being useful the day the network grows.
 */
public final class ReferenceValidator {

  /** What a complete import must contain. */
  public record Expectations(int outlets, int vehicles, int depots, int districts) {
    public static Expectations waypoint() {
      return new Expectations(120, 60, 2, 12);
    }
  }

  private ReferenceValidator() {}

  public static List<ReferenceViolation> validate(ReferenceSnapshot snapshot, Expectations expected) {
    List<ReferenceViolation> violations = new ArrayList<>();
    completeness(snapshot, expected, violations);
    referentialIntegrity(snapshot, violations);
    windows(snapshot, violations);
    windowFeasibility(snapshot, violations);
    capacities(snapshot, violations);
    travelCoverage(snapshot, violations);
    allowanceCoverage(snapshot, violations);
    fleetSanity(snapshot, violations);
    calendarContinuity(snapshot, violations);
    return List.copyOf(violations);
  }

  /** 1. Completeness. */
  private static void completeness(
      ReferenceSnapshot s, Expectations expected, List<ReferenceViolation> out) {
    check(out, "completeness", "outlets", s.allOutlets().size(), expected.outlets());
    check(out, "completeness", "vehicles", s.allVehicles().size(), expected.vehicles());
    check(out, "completeness", "depots", s.allDepots().size(), expected.depots());
    check(out, "completeness", "districts", s.allDistricts().size(), expected.districts());
  }

  private static void check(
      List<ReferenceViolation> out, String rule, String subject, int actual, int expected) {
    if (actual != expected) {
      out.add(
          new ReferenceViolation(rule, subject, "expected " + expected + ", found " + actual));
    }
  }

  /** 2. Referential integrity. */
  private static void referentialIntegrity(ReferenceSnapshot s, List<ReferenceViolation> out) {
    Set<String> brands = new HashSet<>(s.brands());
    for (Outlet outlet : s.allOutlets()) {
      if (!brands.contains(outlet.brandCode())) {
        out.add(new ReferenceViolation("referential", outlet.id(), "unknown brand " + outlet.brandCode()));
      }
      if (s.district(outlet.districtName()).isEmpty()) {
        out.add(
            new ReferenceViolation("referential", outlet.id(), "unknown district " + outlet.districtName()));
      }
    }
    for (Vehicle vehicle : s.allVehicles()) {
      if (s.depot(vehicle.depot()).isEmpty()) {
        out.add(new ReferenceViolation("referential", vehicle.id(), "unknown depot " + vehicle.depot()));
      }
    }
    for (District district : s.allDistricts()) {
      if (s.depot(district.depot()).isEmpty()) {
        out.add(
            new ReferenceViolation("referential", district.name(), "unknown depot " + district.depot()));
      }
    }
  }

  /** 3. Windows are coherent, and a mall outlet carries the window it is bound by. */
  private static void windows(ReferenceSnapshot s, List<ReferenceViolation> out) {
    for (Outlet outlet : s.allOutlets()) {
      if (outlet.parkingConstraint() == ParkingConstraint.MALL_DOCK
          && outlet.mallWindow().isEmpty()) {
        out.add(new ReferenceViolation("windows", outlet.id(), "mall outlet has no mall window"));
      }
      if (outlet.effectiveWindow().isEmpty()) {
        out.add(
            new ReferenceViolation(
                "windows",
                outlet.id(),
                "outlet window " + outlet.window() + " does not overlap its mall window"));
      }
    }
  }

  /**
   * 4. Window feasibility. An outlet whose window is shorter than the handling
   * its brand and dock require can never be served, and discovering that at
   * 03:30 is worse than refusing the import.
   */
  private static void windowFeasibility(ReferenceSnapshot s, List<ReferenceViolation> out) {
    for (Outlet outlet : s.allOutlets()) {
      var window = outlet.effectiveWindow();
      var allowance = s.serviceAllowance(outlet.brandCode(), outlet.dockType());
      if (window.isEmpty() || allowance.isEmpty()) {
        continue; // reported by rules 3 and 7
      }
      long available = window.get().durationMinutes();
      BigDecimal needed = allowance.get().minutes();
      if (BigDecimal.valueOf(available).compareTo(needed) < 0) {
        out.add(
            new ReferenceViolation(
                "window-feasibility",
                outlet.id(),
                "window is " + available + " min but " + outlet.brandCode() + " at "
                    + outlet.dockType().code() + " needs " + needed + " min"));
      }
    }
  }

  /** 5. Capacities and fuel figures are positive. */
  private static void capacities(ReferenceSnapshot s, List<ReferenceViolation> out) {
    for (Vehicle v : s.allVehicles()) {
      if (v.weightCapKg().signum() <= 0 || v.volumeCapM3().signum() <= 0) {
        out.add(new ReferenceViolation("capacity", v.id(), "weight and volume must be positive"));
      }
      if (v.kmPerL().signum() <= 0) {
        out.add(new ReferenceViolation("capacity", v.id(), "km per litre must be positive"));
      }
      if (v.weeklyFuelQuotaL().signum() < 0) {
        out.add(new ReferenceViolation("capacity", v.id(), "fuel quota must not be negative"));
      }
    }
  }

  /** 6. Travel coverage: every district an outlet sits in must be reachable. */
  private static void travelCoverage(ReferenceSnapshot s, List<ReferenceViolation> out) {
    Set<String> needed = new HashSet<>();
    for (Outlet outlet : s.allOutlets()) {
      needed.add(outlet.districtName());
    }
    for (String district : needed) {
      if (s.travelProfile(district).isEmpty()) {
        out.add(
            new ReferenceViolation(
                "travel-coverage", district, "outlets exist here but there is no travel profile"));
      }
    }
  }

  /** 7. An allowance exists for every brand and dock combination actually in use. */
  private static void allowanceCoverage(ReferenceSnapshot s, List<ReferenceViolation> out) {
    Set<String> seen = new HashSet<>();
    for (Outlet outlet : s.allOutlets()) {
      String key = ReferenceSnapshot.allowanceKey(outlet.brandCode(), outlet.dockType());
      if (!seen.add(key)) {
        continue;
      }
      if (s.serviceAllowance(outlet.brandCode(), outlet.dockType()).isEmpty()) {
        out.add(
            new ReferenceViolation(
                "allowance-coverage",
                key,
                "outlets use this brand and dock but no service allowance exists"));
      }
    }
  }

  /**
   * 8. Fleet sanity. A depot with van-only outlets and no van, or with chilled
   * demand and no reefer, is a plan that cannot exist.
   */
  private static void fleetSanity(ReferenceSnapshot s, List<ReferenceViolation> out) {
    for (Depot depot : s.allDepots()) {
      DepotCode code = depot.code();
      List<Vehicle> fleet = s.vehiclesOf(code);
      boolean needsVan = s.outletsOf(code).stream().anyMatch(Outlet::requiresVan);
      if (needsVan && fleet.stream().noneMatch(v -> v.type() == VehicleType.VAN)) {
        out.add(
            new ReferenceViolation("fleet-sanity", code.value(), "van-only outlets but no van"));
      }
      if (!s.outletsOf(code).isEmpty()
          && fleet.stream().noneMatch(v -> v.temperature() == TemperatureCapability.REEFER)) {
        out.add(
            new ReferenceViolation("fleet-sanity", code.value(), "no refrigerated vehicle"));
      }
    }
  }

  /** 9. The calendar is contiguous and its ISO week agrees with its date. */
  private static void calendarContinuity(ReferenceSnapshot s, List<ReferenceViolation> out) {
    List<LocalDate> dates = s.allDays().stream().map(CalendarDay::date).sorted().toList();
    if (dates.isEmpty()) {
      out.add(new ReferenceViolation("calendar", "calendar", "no dates supplied"));
      return;
    }
    for (int i = 1; i < dates.size(); i++) {
      LocalDate previous = dates.get(i - 1);
      LocalDate current = dates.get(i);
      if (!previous.plusDays(1).equals(current)) {
        out.add(
            new ReferenceViolation(
                "calendar", current.toString(), "gap after " + previous));
      }
    }
    var iso = java.time.temporal.WeekFields.ISO;
    for (CalendarDay day : s.allDays()) {
      if (day.isoWeek() != day.date().get(iso.weekOfWeekBasedYear())
          || day.isoYear() != day.date().get(iso.weekBasedYear())) {
        out.add(
            new ReferenceViolation(
                "calendar",
                day.date().toString(),
                "iso week " + day.isoYear() + "-W" + day.isoWeek() + " disagrees with the date"));
      }
    }
  }
}
