package com.waypoint.dispatch.referencedata.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * What other modules see of reference data.
 *
 * <p>These are deliberately not the domain types. A module imports another
 * module's contract and nothing else, so Planning can depend on an outlet's
 * window without depending on how reference data models an outlet. The cost is
 * a mapping layer; the benefit is that reference data can change shape without
 * breaking every module that reads it.
 */
public final class ReferenceViews {
  private ReferenceViews() {}

  /**
   * @param effectiveWindowOpen the overlap of the outlet window and any mall
   *     window (R-PLN-29), already resolved so no caller has to remember to
   *     intersect them. Empty means the outlet cannot be served at all.
   */
  public record OutletView(
      String outletId,
      String brandCode,
      String districtName,
      String depotCode,
      String dockType,
      String parkingConstraint,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<LocalTime> effectiveWindowOpen,
      Optional<LocalTime> effectiveWindowClose,
      boolean vanOnly) {}

  public record VehicleView(
      String vehicleId,
      String vehicleType,
      String temperatureCapability,
      BigDecimal weightCapKg,
      BigDecimal volumeCapM3,
      BigDecimal kmPerL,
      BigDecimal weeklyFuelQuotaL,
      String depotCode,
      boolean refrigerated,
      boolean van) {}

  /** Inputs to the published trip time formula. Keyed by district alone (decision D8). */
  public record TravelView(
      String districtName,
      String depotCode,
      BigDecimal depotToDistrictFreeflowMin,
      BigDecimal interStopFreeflowMin,
      BigDecimal depotToDistrictKm,
      BigDecimal interStopKm,
      String roadClass,
      BigDecimal freeFlowKmh) {}

  /** A planning allowance, not an observed duration (assumption A-16). */
  public record AllowanceView(String brandCode, String dockType, BigDecimal minutes) {}

  public record CalendarDayView(
      LocalDate date,
      boolean operating,
      boolean holiday,
      boolean payday,
      boolean monsoon,
      String festival,
      BigDecimal festivalRamp,
      int isoYear,
      int isoWeek,
      boolean generated) {}

  /** Travel speed as a percentage of free flow, by district, hour and monsoon (D9). */
  public record TrafficSpeedView(String districtName, int hour, boolean monsoon, BigDecimal speedIndex) {}

  /** The day's road disruption for a district; 100 is clear roads (D9). */
  public record RoadConditionView(String districtName, LocalDate date, BigDecimal disruptionIndex) {}
}
