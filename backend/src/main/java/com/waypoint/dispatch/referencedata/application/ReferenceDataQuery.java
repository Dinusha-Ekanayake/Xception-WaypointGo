package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.AllowanceView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TravelView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.referencedata.domain.CalendarDay;
import com.waypoint.dispatch.referencedata.domain.DeliveryWindow;
import com.waypoint.dispatch.referencedata.domain.OperatingCalendarPolicy;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import com.waypoint.dispatch.referencedata.domain.TemperatureCapability;
import com.waypoint.dispatch.referencedata.domain.TravelProfile;
import com.waypoint.dispatch.referencedata.domain.Vehicle;
import com.waypoint.dispatch.referencedata.domain.VehicleType;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.shared.domain.DepotCode;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads reference data, from memory for the current version and from the
 * database for a historical one.
 *
 * <p>Historical versions are deliberately not cached: replaying an old decision
 * is rare and should not cost memory that the hot path needs.
 */
@Component
public class ReferenceDataQuery implements ReferenceQuery {
  private final ReferenceCache cache;
  private final ReferenceVersionReader reader;
  private final Database database;
  private final ReferenceBootstrap bootstrap;

  public ReferenceDataQuery(
      ReferenceCache cache,
      ReferenceVersionReader reader,
      Database database,
      ReferenceBootstrap bootstrap) {
    this.cache = cache;
    this.reader = reader;
    this.database = database;
    this.bootstrap = bootstrap;
  }

  @Override
  public Optional<UUID> currentVersionId() {
    return cache.loadedVersionId();
  }

  private ReferenceSnapshot snapshot(UUID versionId) {
    if (versionId == null) {
      bootstrap.refreshIfStale();
      return cache
          .current()
          .orElseThrow(
              () ->
                  new DomainException(
                      ErrorCode.DEPENDENCY_UNAVAILABLE,
                      "No reference version is loaded. Run import-reference."));
    }
    return cache
        .current()
        .filter(s -> s.versionId().equals(versionId))
        .or(() -> database.readAs(ModuleRole.REF, null, () -> reader.load(versionId)))
        .orElseThrow(
            () ->
                new DomainException(ErrorCode.NOT_FOUND, "Unknown reference version " + versionId));
  }

  @Override
  public Optional<OutletView> outlet(String outletId, UUID versionId) {
    ReferenceSnapshot s = snapshot(versionId);
    return s.outlet(outletId).map(o -> toOutletView(s, o));
  }

  @Override
  public Optional<VehicleView> vehicle(String vehicleId, UUID versionId) {
    return snapshot(versionId).vehicle(vehicleId).map(ReferenceDataQuery::toVehicleView);
  }

  @Override
  public List<OutletView> outletsOfDepot(String depotCode, UUID versionId) {
    ReferenceSnapshot s = snapshot(versionId);
    return s.outletsOf(new DepotCode(depotCode)).stream().map(o -> toOutletView(s, o)).toList();
  }

  @Override
  public List<VehicleView> vehiclesOfDepot(String depotCode, UUID versionId) {
    return snapshot(versionId).vehiclesOf(new DepotCode(depotCode)).stream()
        .map(ReferenceDataQuery::toVehicleView)
        .toList();
  }

  /** R-FLT-03: a vehicle in the workshop cannot be allocated. */
  @Override
  public List<VehicleView> availableVehicles(String depotCode, LocalDate date, UUID versionId) {
    Set<String> unavailable = unavailableOn(date);
    return snapshot(versionId).vehiclesOf(new DepotCode(depotCode)).stream()
        .filter(v -> !unavailable.contains(v.id()))
        .map(ReferenceDataQuery::toVehicleView)
        .toList();
  }

  private Set<String> unavailableOn(LocalDate date) {
    List<Map<String, Object>> rows =
        database.readAs(
            ModuleRole.REF,
            null,
            () ->
                database.query(
                    "SELECT vehicle_id FROM ref.vehicle_day_status"
                        + " WHERE service_date = ? AND status <> 'available'",
                    java.sql.Date.valueOf(date)));
    Set<String> ids = new HashSet<>();
    for (Map<String, Object> row : rows) {
      ids.add((String) row.get("vehicle_id"));
    }
    return ids;
  }

  @Override
  public Optional<TravelView> travelProfile(String districtName, UUID versionId) {
    ReferenceSnapshot s = snapshot(versionId);
    return s.travelProfile(districtName)
        .map(
            p ->
                new TravelView(
                    p.districtName(),
                    s.district(p.districtName()).map(d -> d.depot().value()).orElse(null),
                    p.depotToDistrictFreeflowMin(),
                    p.interStopFreeflowMin(),
                    p.depotToDistrictKm(),
                    p.interStopKm()));
  }

  @Override
  public Optional<AllowanceView> serviceAllowance(
      String brandCode, String dockType, UUID versionId) {
    return snapshot(versionId)
        .serviceAllowance(
            brandCode, com.waypoint.dispatch.referencedata.domain.DockType.parse(dockType))
        .map(a -> new AllowanceView(a.brandCode(), a.dockType().code(), a.minutes()));
  }

  @Override
  public Optional<CalendarDayView> day(LocalDate date) {
    return snapshot(null).day(date).map(ReferenceDataQuery::toCalendarView);
  }

  /**
   * R-CAL-03: beyond the supplied range the policy answers rather than the
   * calendar going silent, and the generated day is marked as such.
   */
  @Override
  public boolean isOperating(LocalDate date) {
    return snapshot(null)
        .day(date)
        .map(CalendarDay::operating)
        .orElseGet(() -> OperatingCalendarPolicy.operatesOn(date));
  }

  @Override
  public LocalDate nextOperatingDay(LocalDate from) {
    LocalDate candidate = from;
    for (int i = 0; i < 14; i++) {
      if (isOperating(candidate)) {
        return candidate;
      }
      candidate = candidate.plusDays(1);
    }
    throw new DomainException(
        ErrorCode.VALIDATION_FAILED, "No operating day found within 14 days of " + from);
  }

  // ---- mapping to contract views ----

  private static OutletView toOutletView(ReferenceSnapshot s, Outlet o) {
    Optional<DeliveryWindow> effective = o.effectiveWindow();
    return new OutletView(
        o.id(),
        o.brandCode(),
        o.districtName(),
        s.district(o.districtName()).map(d -> d.depot().value()).orElse(null),
        o.dockType().code(),
        o.parkingConstraint().code(),
        o.window().open(),
        o.window().close(),
        effective.map(DeliveryWindow::open),
        effective.map(DeliveryWindow::close),
        o.requiresVan());
  }

  private static VehicleView toVehicleView(Vehicle v) {
    return new VehicleView(
        v.id(),
        v.type().code(),
        v.temperature().code(),
        v.weightCapKg(),
        v.volumeCapM3(),
        v.kmPerL(),
        v.weeklyFuelQuotaL(),
        v.depot().value(),
        v.temperature() == TemperatureCapability.REEFER,
        v.type() == VehicleType.VAN);
  }

  private static CalendarDayView toCalendarView(CalendarDay d) {
    return new CalendarDayView(
        d.date(),
        d.operating(),
        d.holiday(),
        d.payday(),
        d.monsoon(),
        d.festival(),
        d.festivalRamp(),
        d.isoYear(),
        d.isoWeek(),
        d.generated());
  }
}
