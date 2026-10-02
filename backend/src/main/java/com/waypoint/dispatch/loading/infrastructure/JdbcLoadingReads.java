package com.waypoint.dispatch.loading.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Loading's reads for the dock board, the load sheet and the shortfall list.
 * Row-level security limits every row to the reader's depots; nothing here
 * filters after a broad read (architecture rule 7).
 */
@Repository
public class JdbcLoadingReads {
  private final Database database;

  public JdbcLoadingReads(Database database) {
    this.database = database;
  }

  private static final String LATEST_CHECK =
      """
      LEFT JOIN LATERAL (
          SELECT ic.status, ic.attempt, ic.units, ic.recorded_at, ic.actor_user_id
          FROM loading.item_checks ic
          WHERE ic.trip_id = i.trip_id AND ic.plan_version = i.plan_version
            AND ic.order_id = i.order_id AND ic.line_no = i.line_no
          ORDER BY ic.attempt DESC LIMIT 1) c ON true
      """;

  /** One row per current trip of a depot and day, with the counts the board shows. */
  public List<Map<String, Object>> readyTrips(String depotCode, LocalDate serviceDate) {
    return database.query(
        """
        SELECT t.trip_id, t.vehicle_id, t.trip_number, t.trips_for_vehicle, t.planned_departure,
               t.brand_code, t.district_name, t.temperature, t.dock_code, t.weight_cap_kg, t.volume_cap_m3,
               s.status AS phase, s.holder_user_id, s.holder_name, s.holder_code, s.held_since, s.holder_active_at,
               s.released_at, s.row_version,
               agg.order_count, agg.stop_count, agg.weight_kg, agg.volume_m3,
               lines.pending, lines.flagged
        FROM loading.trips t
        JOIN loading.sessions s ON s.trip_id = t.trip_id
        JOIN LATERAL (
            SELECT count(*) AS order_count, count(DISTINCT st.stop_sequence) AS stop_count,
                   coalesce(sum(st.weight_kg), 0) AS weight_kg, coalesce(sum(st.volume_m3), 0) AS volume_m3
            FROM loading.stops st
            WHERE st.trip_id = t.trip_id AND st.plan_version = t.plan_version) agg ON true
        JOIN LATERAL (
            SELECT count(*) FILTER (WHERE coalesce(c.status, 'pending') = 'pending') AS pending,
                   count(*) FILTER (WHERE c.status IN ('missing','damaged','short','does_not_fit')) AS flagged
            FROM loading.items i
            """
            + LATEST_CHECK
            + """
            WHERE i.trip_id = t.trip_id AND i.plan_version = t.plan_version) lines ON true
        WHERE t.depot_code = ? AND t.service_date = ? AND t.superseded_at IS NULL
        ORDER BY t.planned_departure, t.vehicle_id, t.trip_number
        """,
        depotCode,
        Date.valueOf(serviceDate));
  }

  /** The current trip with its session, or null when absent or out of scope. */
  public Map<String, Object> trip(UUID tripId) {
    return database.queryOne(
        """
        SELECT t.trip_id, t.plan_id, t.plan_version, t.depot_code, t.service_date, t.vehicle_id,
               t.trip_number, t.trips_for_vehicle, t.brand_code, t.district_name, t.temperature,
               t.planned_departure, t.dock_code, t.weight_cap_kg, t.volume_cap_m3,
               s.status AS phase, s.holder_user_id, s.holder_name, s.holder_code, s.held_since, s.holder_active_at,
               s.released_at, s.row_version
        FROM loading.trips t
        JOIN loading.sessions s ON s.trip_id = t.trip_id
        WHERE t.trip_id = ? AND t.superseded_at IS NULL
        """,
        tripId);
  }

  /** The orders of the current version, last stop first (D-L). */
  public List<Map<String, Object>> stops(UUID tripId, int planVersion) {
    return database.query(
        """
        SELECT order_id, stop_sequence, order_ref, outlet_id, temperature, item_count, weight_kg,
               volume_m3, planned_arrival
        FROM loading.stops
        WHERE trip_id = ? AND plan_version = ?
        ORDER BY stop_sequence DESC, order_ref
        """,
        tripId,
        planVersion);
  }

  /** Every item line of the version with its latest check. */
  public List<Map<String, Object>> items(UUID tripId, int planVersion) {
    return database.query(
        """
        SELECT i.order_id, i.line_no, i.product_id, i.units,
               c.status, c.attempt, c.units AS loaded_units, c.recorded_at, c.actor_user_id
        FROM loading.items i
        """
            + LATEST_CHECK
            + """
        WHERE i.trip_id = ? AND i.plan_version = ?
        ORDER BY i.order_id, i.line_no
        """,
        tripId,
        planVersion);
  }

  public List<Map<String, Object>> shortfalls(String depotCode, boolean openOnly) {
    return database.query(
        """
        SELECT f.shortfall_id, f.trip_id, f.order_id, f.line_no, f.kind, f.missing_units, f.reason,
               f.reported_by, f.reported_at, f.resolved_at
        FROM loading.shortfalls f
        JOIN loading.sessions s ON s.trip_id = f.trip_id
        WHERE s.depot_code = ? AND (NOT ? OR f.resolved_at IS NULL)
        ORDER BY f.reported_at DESC
        LIMIT 200
        """,
        depotCode,
        openOnly);
  }
}
