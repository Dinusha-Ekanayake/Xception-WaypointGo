package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.domain.DepotCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One immutable version of the master data (decision D1).
 *
 * <p>A published plan keeps the snapshot it was built against, so re-reading
 * history never silently changes a past decision. Nothing here is mutable:
 * publishing a new version swaps a pointer, it does not edit a snapshot.
 */
public final class ReferenceSnapshot {
  private final UUID versionId;
  private final Map<String, Depot> depots;
  private final Map<String, District> districts;
  private final Map<String, Outlet> outlets;
  private final Map<String, Vehicle> vehicles;
  private final Map<String, TravelProfile> travel;
  private final Map<String, ServiceAllowance> allowances;
  private final Map<LocalDate, CalendarDay> calendar;
  private final List<String> brands;

  public ReferenceSnapshot(
      UUID versionId,
      List<String> brands,
      List<Depot> depots,
      List<District> districts,
      List<Outlet> outlets,
      List<Vehicle> vehicles,
      List<TravelProfile> travel,
      List<ServiceAllowance> allowances,
      List<CalendarDay> calendar) {
    this.versionId = versionId;
    this.brands = List.copyOf(brands);
    this.depots = index(depots, d -> d.code().value());
    this.districts = index(districts, District::name);
    this.outlets = index(outlets, Outlet::id);
    this.vehicles = index(vehicles, Vehicle::id);
    this.travel = index(travel, TravelProfile::districtName);
    this.allowances = index(allowances, a -> allowanceKey(a.brandCode(), a.dockType()));
    this.calendar = index(calendar, CalendarDay::date);
  }

  private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
    return values.stream()
        .collect(Collectors.collectingAndThen(Collectors.toMap(key, v -> v, (a, b) -> a), Map::copyOf));
  }

  static String allowanceKey(String brandCode, DockType dockType) {
    return brandCode + "|" + dockType.name();
  }

  public UUID versionId() {
    return versionId;
  }

  public List<String> brands() {
    return brands;
  }

  public Optional<Outlet> outlet(String id) {
    return Optional.ofNullable(outlets.get(id));
  }

  public Optional<Vehicle> vehicle(String id) {
    return Optional.ofNullable(vehicles.get(id));
  }

  public Optional<District> district(String name) {
    return Optional.ofNullable(districts.get(name));
  }

  public Optional<Depot> depot(DepotCode code) {
    return Optional.ofNullable(depots.get(code.value()));
  }

  public Optional<TravelProfile> travelProfile(String districtName) {
    return Optional.ofNullable(travel.get(districtName));
  }

  public Optional<ServiceAllowance> serviceAllowance(String brandCode, DockType dockType) {
    return Optional.ofNullable(allowances.get(allowanceKey(brandCode, dockType)));
  }

  public Optional<CalendarDay> day(LocalDate date) {
    return Optional.ofNullable(calendar.get(date));
  }

  public java.util.Collection<Outlet> allOutlets() {
    return outlets.values();
  }

  public java.util.Collection<Vehicle> allVehicles() {
    return vehicles.values();
  }

  public java.util.Collection<District> allDistricts() {
    return districts.values();
  }

  public java.util.Collection<Depot> allDepots() {
    return depots.values();
  }

  public java.util.Collection<TravelProfile> allTravelProfiles() {
    return travel.values();
  }

  public java.util.Collection<ServiceAllowance> allAllowances() {
    return allowances.values();
  }

  public java.util.Collection<CalendarDay> allDays() {
    return calendar.values();
  }

  /** The last date the supplied calendar covers. Past it, days come from the extension policy. */
  public Optional<LocalDate> lastSuppliedDay() {
    return calendar.keySet().stream().max(LocalDate::compareTo);
  }

  /**
   * Whole days of supplied calendar left after {@code today}; negative once it has
   * run out (PLT-07).
   */
  public long calendarDaysRemaining(LocalDate today) {
    return lastSuppliedDay()
        .map(last -> java.time.temporal.ChronoUnit.DAYS.between(today, last))
        .orElse(0L);
  }

  /** Outlets served by a depot, via their district (decision D8). */
  public List<Outlet> outletsOf(DepotCode depot) {
    return outlets.values().stream()
        .filter(o -> district(o.districtName()).map(d -> d.depot().equals(depot)).orElse(false))
        .toList();
  }

  public List<Vehicle> vehiclesOf(DepotCode depot) {
    return vehicles.values().stream().filter(v -> v.depot().equals(depot)).toList();
  }

}
