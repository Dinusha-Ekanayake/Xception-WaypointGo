package com.waypoint.dispatch.referencedata.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.referencedata.domain.CalendarDay;
import com.waypoint.dispatch.referencedata.domain.Depot;
import com.waypoint.dispatch.referencedata.domain.District;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import com.waypoint.dispatch.referencedata.domain.ServiceAllowance;
import com.waypoint.dispatch.referencedata.domain.TravelProfile;
import com.waypoint.dispatch.referencedata.domain.Vehicle;
import java.sql.Time;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes one reference version.
 *
 * <p>Append only. Rows are inserted against a fresh version id and the current
 * pointer is then moved, so an already published version is never edited. Must
 * be called inside a transaction: the caller owns the boundary, not this class.
 */
@Component
public class ReferenceVersionWriter {
  private final Database database;

  public ReferenceVersionWriter(Database database) {
    this.database = database;
  }

  /** Returns the existing version id when this exact content was already imported. */
  public Optional<UUID> findByHash(String contentHash) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT reference_version_id FROM ref.reference_versions WHERE content_hash = ?",
            contentHash);
    return row == null
        ? Optional.empty()
        : Optional.of((UUID) row.get("reference_version_id"));
  }

  public UUID insertVersion(String sourceLabel, String contentHash, UUID importedBy) {
    UUID versionId = UUID.randomUUID();
    database.update(
        """
        INSERT INTO ref.reference_versions
            (reference_version_id, source_label, content_hash, imported_by, is_current)
        VALUES (?, ?, ?, ?, false)
        """,
        versionId,
        sourceLabel,
        contentHash,
        importedBy);
    return versionId;
  }

  public void writeRows(UUID versionId, ReferenceSnapshot snapshot) {
    registerIdentities(snapshot);

    for (String brand : snapshot.brands()) {
      database.update(
          "INSERT INTO ref.brands (reference_version_id, brand_code, display_name) VALUES (?, ?, ?)",
          versionId,
          brand,
          "Waypoint " + brand);
    }
    for (Depot depot : snapshot.allDepots()) {
      database.update(
          """
          INSERT INTO ref.depots (reference_version_id, depot_code, display_name, timezone_name)
          VALUES (?, ?, ?, ?)
          """,
          versionId,
          depot.code().value(),
          depot.displayName(),
          depot.timezone().getId());
    }
    for (District district : snapshot.allDistricts()) {
      database.update(
          "INSERT INTO ref.districts (reference_version_id, district_name, depot_code) VALUES (?, ?, ?)",
          versionId,
          district.name(),
          district.depot().value());
    }
    for (Outlet outlet : snapshot.allOutlets()) {
      database.update(
          """
          INSERT INTO ref.outlets
              (reference_version_id, outlet_id, brand_code, district_name, dock_type,
               parking_constraint, mall_window_open, mall_window_close,
               window_open_time, window_close_time)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          versionId,
          outlet.id(),
          outlet.brandCode(),
          outlet.districtName(),
          outlet.dockType().code(),
          outlet.parkingConstraint().code(),
          outlet.mallWindow().map(w -> Time.valueOf(w.open())).orElse(null),
          outlet.mallWindow().map(w -> Time.valueOf(w.close())).orElse(null),
          Time.valueOf(outlet.window().open()),
          Time.valueOf(outlet.window().close()));
    }
    for (Vehicle vehicle : snapshot.allVehicles()) {
      database.update(
          """
          INSERT INTO ref.vehicles
              (reference_version_id, vehicle_id, vehicle_type, temperature_capability,
               weight_cap_kg, volume_cap_m3, fuel_type, km_per_l, weekly_fuel_quota_l, depot_code)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          versionId,
          vehicle.id(),
          vehicle.type().code(),
          vehicle.temperature().code(),
          vehicle.weightCapKg(),
          vehicle.volumeCapM3(),
          vehicle.fuelType(),
          vehicle.kmPerL(),
          vehicle.weeklyFuelQuotaL(),
          vehicle.depot().value());
    }
    for (TravelProfile profile : snapshot.allTravelProfiles()) {
      database.update(
          """
          INSERT INTO ref.district_travel
              (reference_version_id, district_name, road_class, free_flow_kmh,
               depot_to_district_km, depot_to_district_freeflow_min,
               inter_stop_km, inter_stop_freeflow_min)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          versionId,
          profile.districtName(),
          profile.roadClass(),
          profile.freeFlowKmh(),
          profile.depotToDistrictKm(),
          profile.depotToDistrictFreeflowMin(),
          profile.interStopKm(),
          profile.interStopFreeflowMin());
    }
    for (ServiceAllowance allowance : snapshot.allAllowances()) {
      database.update(
          """
          INSERT INTO ref.service_allowances
              (reference_version_id, brand_code, dock_type, service_allowance_min)
          VALUES (?, ?, ?, ?)
          """,
          versionId,
          allowance.brandCode(),
          allowance.dockType().code(),
          allowance.minutes());
    }
    writeCalendar(snapshot);
  }

  /**
   * Stable identities, inserted once and referenced by every version. Operational
   * rows point here, never at a versioned row, so a plan keeps pointing at
   * OUT001 while its attributes may differ between versions.
   */
  private void registerIdentities(ReferenceSnapshot snapshot) {
    for (Outlet outlet : snapshot.allOutlets()) {
      database.update(
          "INSERT INTO ref.outlet_registry (outlet_id) VALUES (?) ON CONFLICT DO NOTHING",
          outlet.id());
    }
    for (Vehicle vehicle : snapshot.allVehicles()) {
      database.update(
          "INSERT INTO ref.vehicle_registry (vehicle_id) VALUES (?) ON CONFLICT DO NOTHING",
          vehicle.id());
    }
  }

  /**
   * The calendar is a date-keyed series and is deliberately not versioned
   * (decision D1): correcting one day must not mint a new version of the world.
   */
  private void writeCalendar(ReferenceSnapshot snapshot) {
    for (CalendarDay day : snapshot.allDays()) {
      database.update(
          """
          INSERT INTO ref.calendar_days
              (calendar_date, dow, dow_name, is_weekend, iso_year, iso_week, is_payday,
               festival, festival_ramp, is_holiday, monsoon, is_operating, is_generated)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (calendar_date) DO NOTHING
          """,
          java.sql.Date.valueOf(day.date()),
          day.dayOfWeek(),
          day.date().getDayOfWeek().getDisplayName(
              java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH),
          day.weekend(),
          day.isoYear(),
          day.isoWeek(),
          day.payday(),
          day.festival(),
          day.festivalRamp(),
          day.holiday(),
          day.monsoon(),
          day.operating(),
          day.generated());
    }
  }

  /** Moves the current pointer. The partial index guarantees only one current version. */
  public void makeCurrent(UUID versionId) {
    database.update("UPDATE ref.reference_versions SET is_current = false WHERE is_current");
    database.update(
        "UPDATE ref.reference_versions SET is_current = true WHERE reference_version_id = ?",
        versionId);
  }
}
