package com.waypoint.dispatch.referencedata.contract;

import com.waypoint.dispatch.referencedata.contract.ReferenceViews.AllowanceView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
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

  /** The fleet a depot can actually use on a date: vehicles minus workshop and unavailable. */
  List<VehicleView> availableVehicles(String depotCode, LocalDate date, UUID versionId);

  Optional<TravelView> travelProfile(String districtName, UUID versionId);

  Optional<AllowanceView> serviceAllowance(String brandCode, String dockType, UUID versionId);

  Optional<CalendarDayView> day(LocalDate date);

  /** R-CAL-01. */
  boolean isOperating(LocalDate date);

  /** R-ORD-08: where an order lands when its requested date does not operate. */
  LocalDate nextOperatingDay(LocalDate from);
}
