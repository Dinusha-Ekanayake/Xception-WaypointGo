package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Current reference snapshot for the admin directory, scoped in SQL. */
@Component
public class AdminReferenceQuery {
  private final Database database;

  public AdminReferenceQuery(Database database) {
    this.database = database;
  }

  public record Depot(String code, String name) {}
  public record DepotDetail(String code, String name, String timezone, UUID referenceVersionId,
      long outletCount, long vehicleCount, long districtCount, long rowVersion) {}
  public record Outlet(String outletId, String brand, String district, String depot, String dockType,
      String parking, LocalTime windowOpen, LocalTime windowClose, LocalTime mallOpen,
      LocalTime mallClose, BigDecimal latitude, BigDecimal longitude) {}
  public record Vehicle(String vehicleId, String depot, String type, String temperature,
      BigDecimal weightCapKg, BigDecimal volumeCapM3, String fuelType, BigDecimal kmPerL,
      BigDecimal weeklyFuelQuotaL, LocalDate statusDate, String dayStatus, long rowVersion) {}

  public List<Depot> depots(UUID actorId) {
    return database.readAs(ModuleRole.REF, actorId, () -> database.query(
        """
        SELECT d.depot_code, d.display_name FROM ref.depots d
          JOIN ref.reference_versions v ON v.reference_version_id = d.reference_version_id
         WHERE v.is_current AND app.actor_has_depot(d.depot_code) ORDER BY d.depot_code
        """).stream().map(r -> new Depot((String) r.get("depot_code"),
        (String) r.get("display_name"))).toList());
  }

  public DepotDetail depot(UUID actorId, String code) {
    Map<String, Object> r = database.readAs(ModuleRole.REF, actorId, () -> database.queryOne(
        """
        SELECT d.depot_code, d.display_name, d.timezone_name, d.reference_version_id,
          (SELECT count(*) FROM ref.outlets o JOIN ref.districts x
             ON x.reference_version_id = o.reference_version_id AND x.district_name = o.district_name
            WHERE o.reference_version_id = d.reference_version_id AND x.depot_code = d.depot_code) AS outlet_count,
          (SELECT count(*) FROM ref.vehicles f WHERE f.reference_version_id = d.reference_version_id
             AND f.depot_code = d.depot_code) AS vehicle_count,
          (SELECT count(*) FROM ref.districts x WHERE x.reference_version_id = d.reference_version_id
             AND x.depot_code = d.depot_code) AS district_count,
          coalesce((SELECT row_version FROM ref.managed_updates u WHERE u.kind = 'depot' AND u.natural_id = d.depot_code), 0) AS row_version
          FROM ref.depots d JOIN ref.reference_versions v ON v.reference_version_id = d.reference_version_id
         WHERE v.is_current AND d.depot_code = ? AND app.actor_has_depot(d.depot_code)
        """, code));
    if (r == null) throw new DomainException(ErrorCode.NOT_FOUND, "No visible depot " + code);
    return new DepotDetail((String) r.get("depot_code"), (String) r.get("display_name"),
        (String) r.get("timezone_name"), (UUID) r.get("reference_version_id"),
        number(r, "outlet_count").longValue(), number(r, "vehicle_count").longValue(),
        number(r, "district_count").longValue(), number(r, "row_version").longValue());
  }

  public Page<Outlet> outlets(UUID actorId, String depot, String brand, String district,
      String dockType, String search, String after, Integer limit) {
    return outlets(actorId, depot, brand, district, dockType, search, after, limit, null);
  }

  private Page<Outlet> outlets(UUID actorId, String depot, String brand, String district,
      String dockType, String search, String after, Integer limit, String exactId) {
    int size = Page.limit(limit);
    String key = key(after);
    List<Outlet> rows = database.readAs(ModuleRole.REF, actorId, () -> database.query(
        """
        SELECT o.outlet_id, o.brand_code, o.district_name, x.depot_code,
               coalesce(detail.dock_type, o.dock_type) AS dock_type,
               o.parking_constraint,
               coalesce(detail.window_open, o.window_open_time) AS window_open_time,
               coalesce(detail.window_close, o.window_close_time) AS window_close_time,
               o.mall_window_open, o.mall_window_close, o.latitude, o.longitude
          FROM ref.outlets o JOIN ref.districts x
            ON x.reference_version_id = o.reference_version_id AND x.district_name = o.district_name
          JOIN ref.reference_versions v ON v.reference_version_id = o.reference_version_id
          LEFT JOIN ref.outlet_details detail ON detail.outlet_id = o.outlet_id
         WHERE v.is_current AND (app.actor_has_depot(x.depot_code) OR app.actor_has_outlet(o.outlet_id))
           AND (?::text IS NULL OR x.depot_code = ?::text)
           AND (?::text IS NULL OR o.brand_code = ?::text)
           AND (?::text IS NULL OR o.district_name = ?::text)
           AND (?::text IS NULL OR coalesce(detail.dock_type, o.dock_type) = ?::text)
           AND (?::text IS NULL OR o.outlet_id ILIKE '%' || ?::text || '%')
           AND (?::text IS NULL OR o.outlet_id = ?::text)
           AND (?::text IS NULL OR o.outlet_id > ?::text)
         ORDER BY o.outlet_id LIMIT ?
        """, depot, depot, brand, brand, district, district, dockType, dockType,
        search, search, exactId, exactId, key, key, size + 1).stream().map(AdminReferenceQuery::outlet).toList());
    return Page.fromOverfetch(rows, size, r -> Cursor.encode(r.outletId()));
  }

  public Outlet outlet(UUID actorId, String id) {
    return outlets(actorId, null, null, null, null, null, null, 1, id).items().stream()
        .filter(o -> o.outletId().equals(id)).findFirst()
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No visible outlet " + id));
  }

  public Page<Vehicle> vehicles(UUID actorId, String depot, LocalDate date, String type,
      String temperature, String status, String search, String after, Integer limit) {
    return vehicles(actorId, depot, date, type, temperature, status, search, after, limit, null);
  }

  private Page<Vehicle> vehicles(UUID actorId, String depot, LocalDate date, String type,
      String temperature, String status, String search, String after, Integer limit, String exactId) {
    int size = Page.limit(limit);
    String key = key(after);
    Date on = date == null ? null : Date.valueOf(date);
    List<Vehicle> rows = database.readAs(ModuleRole.REF, actorId, () -> database.query(
        """
        SELECT f.vehicle_id, f.depot_code, f.vehicle_type, f.temperature_capability,
               f.weight_cap_kg, f.volume_cap_m3, f.fuel_type, f.km_per_l,
               f.weekly_fuel_quota_l, s.service_date, coalesce(s.status, 'available') AS day_status,
               coalesce((SELECT row_version FROM ref.managed_updates u WHERE u.kind = 'vehicle' AND u.natural_id = f.vehicle_id), 0) AS row_version
          FROM ref.vehicles f JOIN ref.reference_versions v ON v.reference_version_id = f.reference_version_id
          LEFT JOIN ref.vehicle_day_status s ON s.vehicle_id = f.vehicle_id AND s.service_date = ?::date
         WHERE v.is_current AND app.actor_has_depot(f.depot_code)
           AND (?::text IS NULL OR f.depot_code = ?::text)
           AND (?::text IS NULL OR f.vehicle_type = ?::text)
           AND (?::text IS NULL OR f.temperature_capability = ?::text)
           AND (?::text IS NULL OR coalesce(s.status, 'available') = ?::text)
           AND (?::text IS NULL OR f.vehicle_id ILIKE '%' || ?::text || '%')
           AND (?::text IS NULL OR f.vehicle_id = ?::text)
           AND (?::text IS NULL OR f.vehicle_id > ?::text)
         ORDER BY f.vehicle_id LIMIT ?
        """, on, depot, depot, type, type, temperature, temperature, status, status,
        search, search, exactId, exactId, key, key, size + 1).stream().map(AdminReferenceQuery::vehicle).toList());
    return Page.fromOverfetch(rows, size, r -> Cursor.encode(r.vehicleId()));
  }

  public Vehicle vehicle(UUID actorId, String id, LocalDate date) {
    return vehicles(actorId, null, date, null, null, null, null, null, 1, id).items().stream()
        .filter(v -> v.vehicleId().equals(id)).findFirst()
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No visible vehicle " + id));
  }

  private static String key(String after) {
    List<String> parts = Cursor.decode(after, 1);
    return parts.isEmpty() ? null : parts.get(0);
  }

  private static Number number(Map<String, Object> row, String name) { return (Number) row.get(name); }

  private static Outlet outlet(Map<String, Object> r) {
    return new Outlet((String) r.get("outlet_id"), (String) r.get("brand_code"),
        (String) r.get("district_name"), (String) r.get("depot_code"), (String) r.get("dock_type"),
        (String) r.get("parking_constraint"), time(r.get("window_open_time")),
        time(r.get("window_close_time")), time(r.get("mall_window_open")),
        time(r.get("mall_window_close")), (BigDecimal) r.get("latitude"),
        (BigDecimal) r.get("longitude"));
  }

  private static Vehicle vehicle(Map<String, Object> r) {
    Date day = (Date) r.get("service_date");
    return new Vehicle((String) r.get("vehicle_id"), (String) r.get("depot_code"),
        (String) r.get("vehicle_type"), (String) r.get("temperature_capability"),
        (BigDecimal) r.get("weight_cap_kg"), (BigDecimal) r.get("volume_cap_m3"),
        (String) r.get("fuel_type"), (BigDecimal) r.get("km_per_l"),
        (BigDecimal) r.get("weekly_fuel_quota_l"), day == null ? null : day.toLocalDate(),
        (String) r.get("day_status"), number(r, "row_version").longValue());
  }

  private static LocalTime time(Object value) {
    return value == null ? null : ((Time) value).toLocalTime();
  }
}
