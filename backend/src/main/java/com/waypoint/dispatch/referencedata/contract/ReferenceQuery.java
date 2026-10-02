package com.waypoint.dispatch.referencedata.contract;

import com.waypoint.dispatch.referencedata.contract.ReferenceViews.AllowanceView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.RoadConditionView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TrafficSpeedView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TravelView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way another module reads reference data.
 *
 * <p>Every lookup optionally takes a version. Omitting it means the current
 * version; naming one replays exactly what that version contained, which is what
 * makes a past decision reproducible rather than merely recorded.
 */
public interface ReferenceQuery {

  /** The version a plan should stamp when it is built. */
  Optional<UUID> currentVersionId();

  Optional<OutletView> outlet(String outletId, UUID versionId);

  Optional<VehicleView> vehicle(String vehicleId, UUID versionId);

  List<OutletView> outletsOfDepot(String depotCode, UUID versionId);

  /**
   * Every vehicle homed at a depot, available or not. Planning needs the whole
   * fleet to tell an order no vehicle could ever carry (unservable, R-PLN-22)
   * from one that only today's fleet cannot (deferred).
   */
  List<VehicleView> vehiclesOfDepot(String depotCode, UUID versionId);

  /** The fleet a depot can actually use on a date: vehicles minus workshop and unavailable. */
  List<VehicleView> availableVehicles(String depotCode, LocalDate date, UUID versionId);

  Optional<TravelView> travelProfile(String districtName, UUID versionId);

  Optional<AllowanceView> serviceAllowance(String brandCode, String dockType, UUID versionId);

  Optional<CalendarDayView> day(LocalDate date);

  /** R-CAL-01. */
  boolean isOperating(LocalDate date);

  /** R-ORD-08: where an order lands when its requested date does not operate. */
  LocalDate nextOperatingDay(LocalDate from);

  /**
   * Every day from {@code from} to {@code to} inclusive. Past the supplied
   * calendar a day comes from the extension policy and is marked generated
   * (R-CAL-03), so the range never has a hole.
   */
  List<CalendarDayView> calendarDays(LocalDate from, LocalDate to);

  /** The speed index by district, hour and monsoon, for a reference version (D9). */
  List<TrafficSpeedView> trafficSpeed(UUID versionId);

  /**
   * The disruption index by district for these dates. A date with no row has no
   * known disruption: the supplied series ends, and nothing forecasts it.
   */
  List<RoadConditionView> roadConditions(LocalDate from, LocalDate to);

  /** The depots of the current version, sorted. */
  List<String> depotCodes();

  /** The brands of the current version, sorted. */
  List<String> brandCodes();
}
