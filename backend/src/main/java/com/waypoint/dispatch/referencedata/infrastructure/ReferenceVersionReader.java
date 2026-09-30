package com.waypoint.dispatch.referencedata.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.referencedata.domain.CalendarDay;
import com.waypoint.dispatch.referencedata.domain.DeliveryWindow;
import com.waypoint.dispatch.referencedata.domain.Depot;
import com.waypoint.dispatch.referencedata.domain.District;
import com.waypoint.dispatch.referencedata.domain.DockType;
import com.waypoint.dispatch.referencedata.domain.Outlet;
import com.waypoint.dispatch.referencedata.domain.ParkingConstraint;
import com.waypoint.dispatch.referencedata.domain.ReferenceSnapshot;
import com.waypoint.dispatch.referencedata.domain.ServiceAllowance;
import com.waypoint.dispatch.referencedata.domain.TemperatureCapability;
import com.waypoint.dispatch.referencedata.domain.TravelProfile;
import com.waypoint.dispatch.referencedata.domain.Vehicle;
import com.waypoint.dispatch.referencedata.domain.VehicleType;
import com.waypoint.dispatch.shared.domain.DepotCode;
import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads a reference version back into a snapshot.
 *
 * <p>Reading as of a named version is what makes a past decision reproducible:
 * a plan records the version it was built against, and this returns exactly what
 * that version contained rather than what the data says today.
 */
@Component
public class ReferenceVersionReader {
  private final Database database;

  public ReferenceVersionReader(Database database) {
    this.database = database;
  }

  public Optional<UUID> currentVersionId() {
    Map<String, Object> row =
        database.queryOne(
            "SELECT reference_version_id FROM ref.reference_versions WHERE is_current");
    return row == null ? Optional.empty() : Optional.of((UUID) row.get("reference_version_id"));
  }

  public Optional<ReferenceSnapshot> load(UUID versionId) {
    if (versionId == null) {
      return Optional.empty();
    }
    List<String> brands =
        database
            .query("SELECT brand_code FROM ref.brands WHERE reference_version_id = ?", versionId)
            .stream()
            .map(r -> (String) r.get("brand_code"))
            .toList();
    if (brands.isEmpty()) {
      return Optional.empty();
    }

    List<Depot> depots =
        database
            .query(
                "SELECT depot_code, display_name, timezone_name FROM ref.depots"
                    + " WHERE reference_version_id = ?",
                versionId)
            .stream()
            .map(
                r ->
                    new Depot(
                        new DepotCode((String) r.get("depot_code")),
                        (String) r.get("display_name"),
                        ZoneId.of((String) r.get("timezone_name"))))
            .toList();

    List<District> districts =
        database
            .query(
                "SELECT district_name, depot_code FROM ref.districts WHERE reference_version_id = ?",
                versionId)
            .stream()
            .map(
                r ->
                    new District(
                        (String) r.get("district_name"),
                        new DepotCode((String) r.get("depot_code"))))
            .toList();

    List<Outlet> outlets =
        database.query("SELECT * FROM ref.outlets WHERE reference_version_id = ?", versionId).stream()
            .map(ReferenceVersionReader::toOutlet)
            .toList();

    List<Vehicle> vehicles =
        database
            .query("SELECT * FROM ref.vehicles WHERE reference_version_id = ?", versionId)
            .stream()
            .map(ReferenceVersionReader::toVehicle)
            .toList();

    List<TravelProfile> travel =
        database
            .query("SELECT * FROM ref.district_travel WHERE reference_version_id = ?", versionId)
            .stream()
            .map(
                r ->
                    new TravelProfile(
                        (String) r.get("district_name"),
                        (String) r.get("road_class"),
                        (BigDecimal) r.get("free_flow_kmh"),
                        (BigDecimal) r.get("depot_to_district_km"),
                        (BigDecimal) r.get("depot_to_district_freeflow_min"),
                        (BigDecimal) r.get("inter_stop_km"),
                        (BigDecimal) r.get("inter_stop_freeflow_min")))
            .toList();

    List<ServiceAllowance> allowances =
        database
            .query("SELECT * FROM ref.service_allowances WHERE reference_version_id = ?", versionId)
            .stream()
            .map(
                r ->
                    new ServiceAllowance(
                        (String) r.get("brand_code"),
                        DockType.parse((String) r.get("dock_type")),
                        (BigDecimal) r.get("service_allowance_min")))
            .toList();

    List<CalendarDay> calendar = calendarWithOverrides();

    return Optional.of(
        new ReferenceSnapshot(
            versionId, brands, depots, districts, outlets, vehicles, travel, allowances, calendar));
  }

  private static Outlet toOutlet(Map<String, Object> r) {
    Optional<DeliveryWindow> mall =
        r.get("mall_window_open") == null
            ? Optional.empty()
            : Optional.of(
                new DeliveryWindow(
                    time(r.get("mall_window_open")), time(r.get("mall_window_close"))));
    return new Outlet(
        (String) r.get("outlet_id"),
        (String) r.get("brand_code"),
        (String) r.get("district_name"),
        DockType.parse((String) r.get("dock_type")),
        ParkingConstraint.parse((String) r.get("parking_constraint")),
        new DeliveryWindow(time(r.get("window_open_time")), time(r.get("window_close_time"))),
        mall);
  }

  private static Vehicle toVehicle(Map<String, Object> r) {
    return new Vehicle(
        (String) r.get("vehicle_id"),
        VehicleType.parse((String) r.get("vehicle_type")),
        TemperatureCapability.parse((String) r.get("temperature_capability")),
        (BigDecimal) r.get("weight_cap_kg"),
        (BigDecimal) r.get("volume_cap_m3"),
        (String) r.get("fuel_type"),
        (BigDecimal) r.get("km_per_l"),
        (BigDecimal) r.get("weekly_fuel_quota_l"),
        new DepotCode((String) r.get("depot_code")));
  }

  /**
   * The supplied and generated calendar, with any recorded override laid on top.
   *
   * <p>Applied here rather than at every read so a constraint evaluation stays in
   * memory. An overridden day is never marked generated: a person decided it, so
   * R-CAL-03's distinction between a real day and an assumed one still holds.
   *
   * <p>An override can name a date the calendar does not reach, which is the
   * normal case for declaring a holiday months ahead. That day is generated from
   * policy first and then overruled.
   */
  private List<CalendarDay> calendarWithOverrides() {
    Map<LocalDate, Boolean> overrides = new java.util.LinkedHashMap<>();
    for (Map<String, Object> r :
        database.query(
            "SELECT calendar_date, is_operating FROM ref.calendar_overrides ORDER BY calendar_date")) {
      overrides.put(
          ((java.sql.Date) r.get("calendar_date")).toLocalDate(), (Boolean) r.get("is_operating"));
    }

    Map<LocalDate, CalendarDay> days = new java.util.LinkedHashMap<>();
    for (Map<String, Object> r :
        database.query("SELECT * FROM ref.calendar_days ORDER BY calendar_date")) {
      CalendarDay day = toCalendarDay(r);
      days.put(day.date(), day);
    }
    for (Map.Entry<LocalDate, Boolean> override : overrides.entrySet()) {
      CalendarDay base =
          days.computeIfAbsent(
              override.getKey(),
              com.waypoint.dispatch.referencedata.domain.OperatingCalendarPolicy::generate);
      days.put(override.getKey(), operating(base, override.getValue()));
    }
    return List.copyOf(days.values());
  }

  private static CalendarDay operating(CalendarDay day, boolean operating) {
    return new CalendarDay(
        day.date(),
        day.dayOfWeek(),
        day.weekend(),
        day.isoYear(),
        day.isoWeek(),
        day.payday(),
        day.festival(),
        day.festivalRamp(),
        day.holiday(),
        day.monsoon(),
        operating,
        false);
  }

  private static CalendarDay toCalendarDay(Map<String, Object> r) {
    return new CalendarDay(
        ((java.sql.Date) r.get("calendar_date")).toLocalDate(),
        ((Number) r.get("dow")).intValue(),
        (Boolean) r.get("is_weekend"),
        ((Number) r.get("iso_year")).intValue(),
        ((Number) r.get("iso_week")).intValue(),
        (Boolean) r.get("is_payday"),
        (String) r.get("festival"),
        (BigDecimal) r.get("festival_ramp"),
        (Boolean) r.get("is_holiday"),
        (Boolean) r.get("monsoon"),
        (Boolean) r.get("is_operating"),
        (Boolean) r.get("is_generated"));
  }

  private static LocalTime time(Object value) {
    return ((Time) value).toLocalTime();
  }
}
