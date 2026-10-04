package com.waypoint.dispatch.referencedata.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.referencedata.domain.ReferenceValidator.Expectations;
import com.waypoint.dispatch.shared.domain.DepotCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Each of the nine import rules must reject a deliberately broken fixture.
 *
 * <p>A validator that has never refused anything is not known to work. These run
 * with no database and no clock, which is the point of keeping the rules in the
 * domain.
 */
class ReferenceValidatorTest {
  private static final DepotCode PELIYAGODA = new DepotCode("Peliyagoda");
  private static final Expectations ONE_OF_EACH = new Expectations(1, 1, 1, 1);

  @Test
  void aCorrectImportHasNoViolations() {
    assertEquals(List.of(), ReferenceValidator.validate(valid(), ONE_OF_EACH));
  }

  @Test
  void rule1_rejectsAnIncompleteImport() {
    // The real network is 120 outlets; this fixture has one.
    var violations = ReferenceValidator.validate(valid(), Expectations.waypoint());

    assertTrue(has(violations, "completeness", "outlets"), violations.toString());
  }

  @Test
  void rule2_rejectsAnOutletInAnUnknownDistrict() {
    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Atlantis", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "referential", "OUT001"));
  }

  @Test
  void rule3_rejectsAMallOutletWithNoMallWindow() {
    var snapshot =
        snapshot(
            List.of(
                new Outlet(
                    "OUT001",
                    "Fresh",
                    "Colombo",
                    DockType.MALL_BAY,
                    ParkingConstraint.MALL_DOCK,
                    new DeliveryWindow(LocalTime.of(9, 0), LocalTime.of(11, 0)),
                    Optional.empty())),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.MALL_BAY, 18)),
            calendar());

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "windows", "OUT001"));
  }

  @Test
  void rule3_rejectsAMallWindowThatDoesNotOverlapTheOutletWindow() {
    var snapshot =
        snapshot(
            List.of(
                new Outlet(
                    "OUT001",
                    "Fresh",
                    "Colombo",
                    DockType.MALL_BAY,
                    ParkingConstraint.MALL_DOCK,
                    new DeliveryWindow(LocalTime.of(5, 0), LocalTime.of(7, 0)),
                    Optional.of(new DeliveryWindow(LocalTime.of(9, 0), LocalTime.of(11, 0))))),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.MALL_BAY, 18)),
            calendar());

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "windows", "OUT001"));
  }

  @Test
  void rule4_rejectsAnOutletThatCanNeverBeServed() {
    // A 10 minute window cannot absorb 16 minutes of handling.
    var snapshot =
        snapshot(
            List.of(
                new Outlet(
                    "OUT001",
                    "Fresh",
                    "Colombo",
                    DockType.STREET,
                    ParkingConstraint.NORMAL,
                    new DeliveryWindow(LocalTime.of(5, 0), LocalTime.of(5, 10)),
                    Optional.empty())),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    var violations = ReferenceValidator.validate(snapshot, ONE_OF_EACH);
    assertTrue(has(violations, "window-feasibility", "OUT001"), violations.toString());
  }

  @Test
  void rule5_rejectsAVehicleWithNoCapacity() {
    var broken =
        new Vehicle(
            "VEH001",
            VehicleType.VAN,
            TemperatureCapability.REEFER,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "diesel",
            new BigDecimal("4.7"),
            new BigDecimal("340"),
            PELIYAGODA);

    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(broken),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "capacity", "VEH001"));
  }

  @Test
  void rule6_rejectsADistrictWithNoTravelProfile() {
    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(van()),
            List.of(),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "travel-coverage", "Colombo"));
  }

  @Test
  void rule7_rejectsAMissingServiceAllowance() {
    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.REAR_DOCK, ParkingConstraint.NORMAL)),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    var violations = ReferenceValidator.validate(snapshot, ONE_OF_EACH);
    assertTrue(has(violations, "allowance-coverage", "Fresh|REAR_DOCK"), violations.toString());
  }

  @Test
  void rule8_rejectsADepotWithVanOnlyOutletsAndNoVan() {
    var truck =
        new Vehicle(
            "VEH001",
            VehicleType.TRUCK,
            TemperatureCapability.REEFER,
            new BigDecimal("5510"),
            new BigDecimal("26.4"),
            "diesel",
            new BigDecimal("4.7"),
            new BigDecimal("340"),
            PELIYAGODA);

    var snapshot =
        snapshot(
            List.of(
                outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.VAN_ONLY)),
            List.of(truck),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    assertTrue(
        has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "fleet-sanity", "Peliyagoda"));
  }

  @Test
  void rule8_rejectsADepotWithNoRefrigeratedVehicle() {
    var ambientOnly =
        new Vehicle(
            "VEH001",
            VehicleType.VAN,
            TemperatureCapability.AMBIENT,
            new BigDecimal("1200"),
            new BigDecimal("6.5"),
            "diesel",
            new BigDecimal("9.1"),
            new BigDecimal("120"),
            PELIYAGODA);

    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(ambientOnly),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            calendar());

    assertTrue(
        has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "fleet-sanity", "Peliyagoda"));
  }

  @Test
  void anEmptyNewDepotDoesNotNeedFleetUntilItServesAnOutlet() {
    ReferenceSnapshot original = valid();
    ReferenceSnapshot expanded = new ReferenceSnapshot(
        UUID.randomUUID(), original.brands(),
        List.of(new Depot(PELIYAGODA, "Peliyagoda", ZoneId.of("Asia/Colombo")),
            new Depot(new DepotCode("GALLE"), "Galle", ZoneId.of("Asia/Colombo"))),
        List.copyOf(original.allDistricts()), List.copyOf(original.allOutlets()),
        List.copyOf(original.allVehicles()), List.copyOf(original.allTravelProfiles()),
        List.copyOf(original.allAllowances()), List.copyOf(original.allDays()));

    assertEquals(List.of(), ReferenceValidator.validate(expanded,
        new Expectations(1, 1, 2, 1)));
  }

  @Test
  void rule9_rejectsAGapInTheCalendar() {
    var withGap =
        List.of(day(LocalDate.of(2026, 2, 9)), day(LocalDate.of(2026, 2, 11)));

    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            withGap);

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "calendar", "2026-02-11"));
  }

  @Test
  void rule9_rejectsAnIsoWeekThatDisagreesWithItsDate() {
    var wrong =
        List.of(
            new CalendarDay(
                LocalDate.of(2026, 2, 9), 0, false, 2026, 52, false, null, BigDecimal.ZERO,
                false, false, true, false));

    var snapshot =
        snapshot(
            List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
            List.of(van()),
            List.of(travel("Colombo")),
            List.of(allowance(DockType.STREET, 16)),
            wrong);

    assertTrue(has(ReferenceValidator.validate(snapshot, ONE_OF_EACH), "calendar", "2026-02-09"));
  }

  // ---- fixtures ----

  private static boolean has(List<ReferenceViolation> violations, String rule, String subject) {
    return violations.stream()
        .anyMatch(v -> v.rule().equals(rule) && v.subject().equals(subject));
  }

  private static ReferenceSnapshot valid() {
    return snapshot(
        List.of(outlet("OUT001", "Fresh", "Colombo", DockType.STREET, ParkingConstraint.NORMAL)),
        List.of(van()),
        List.of(travel("Colombo")),
        List.of(allowance(DockType.STREET, 16)),
        calendar());
  }

  private static ReferenceSnapshot snapshot(
      List<Outlet> outlets,
      List<Vehicle> vehicles,
      List<TravelProfile> travel,
      List<ServiceAllowance> allowances,
      List<CalendarDay> calendar) {
    return new ReferenceSnapshot(
        UUID.randomUUID(),
        List.of("Fresh"),
        List.of(new Depot(PELIYAGODA, "Peliyagoda", ZoneId.of("Asia/Colombo"))),
        List.of(new District("Colombo", PELIYAGODA)),
        outlets,
        vehicles,
        travel,
        allowances,
        calendar);
  }

  private static Outlet outlet(
      String id, String brand, String district, DockType dock, ParkingConstraint parking) {
    return new Outlet(
        id,
        brand,
        district,
        dock,
        parking,
        new DeliveryWindow(LocalTime.of(5, 0), LocalTime.of(7, 30)),
        Optional.empty());
  }

  private static Vehicle van() {
    return new Vehicle(
        "VEH001",
        VehicleType.VAN,
        TemperatureCapability.REEFER,
        new BigDecimal("1200"),
        new BigDecimal("6.5"),
        "diesel",
        new BigDecimal("9.1"),
        new BigDecimal("120"),
        PELIYAGODA);
  }

  private static TravelProfile travel(String district) {
    return new TravelProfile(
        district,
        "urban",
        new BigDecimal("30"),
        new BigDecimal("12"),
        new BigDecimal("24"),
        new BigDecimal("4"),
        new BigDecimal("8"));
  }

  private static ServiceAllowance allowance(DockType dock, int minutes) {
    return new ServiceAllowance("Fresh", dock, BigDecimal.valueOf(minutes));
  }

  private static List<CalendarDay> calendar() {
    return List.of(day(LocalDate.of(2026, 2, 9)), day(LocalDate.of(2026, 2, 10)));
  }

  private static CalendarDay day(LocalDate date) {
    var iso = java.time.temporal.WeekFields.ISO;
    return new CalendarDay(
        date,
        date.getDayOfWeek().getValue() - 1,
        false,
        date.get(iso.weekBasedYear()),
        date.get(iso.weekOfWeekBasedYear()),
        false,
        null,
        BigDecimal.ZERO,
        false,
        false,
        true,
        false);
  }
}
