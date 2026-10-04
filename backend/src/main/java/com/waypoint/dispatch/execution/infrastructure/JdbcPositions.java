package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.execution.domain.PositionFix;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Vehicle positions (R-EXE-18 to R-EXE-21). Append only: there is no update,
 * and removal is the retention function's alone. Row-level security decides
 * who reads which rows; these statements never widen it.
 */
@Repository
public class JdbcPositions {
  /** Fixes kept per vehicle for its direction of travel (R-EXE-22); the first row is the last good fix. */
  static final int RECENT_FIXES = 8;

  /**
   * The last {@value #RECENT_FIXES} good fixes per vehicle, newest first, and
   * whether its trip still has stops ahead. The filter goes in the placeholder.
   */
  private static final String RECENT =
      """
      SELECT r.vehicle_id, r.trip_id, r.latitude, r.longitude, r.heading_deg, r.accuracy_m, r.recorded_at,
             EXISTS (SELECT 1 FROM execution.delivery_records d
                      WHERE d.vehicle_id = r.vehicle_id AND d.service_date = r.service_date
                        AND d.outcome IN ('pending', 'arrived')) AS in_progress
        FROM (SELECT p.vehicle_id, p.trip_id, p.service_date, p.latitude, p.longitude, p.heading_deg,
                     p.accuracy_m, p.recorded_at,
                     ROW_NUMBER() OVER (PARTITION BY p.vehicle_id ORDER BY p.recorded_at DESC) AS rn
                FROM execution.vehicle_positions p
               WHERE NOT p.low_quality AND %s) r
       WHERE r.rn <= %d
       ORDER BY r.vehicle_id, r.recorded_at DESC
      """;

  private final Database database;

  public JdbcPositions(Database database) {
    this.database = database;
  }

  /** @return true when stored, false when the vehicle already has a fix at that instant */
  public boolean insert(
      UUID positionId, String vehicleId, String depotCode, LocalDate serviceDate, UUID tripId, UUID driverId,
      PositionFix fix, boolean lowQuality, Instant receivedAt, UUID commandId) {
    return database.update(
            """
            INSERT INTO execution.vehicle_positions
                (position_id, vehicle_id, depot_code, service_date, trip_id, driver_id, recorded_at, received_at,
                 latitude, longitude, accuracy_m, heading_deg, speed_kmh, low_quality, command_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (vehicle_id, recorded_at) DO NOTHING
            """,
            positionId, vehicleId, depotCode, Date.valueOf(serviceDate), tripId, driverId,
            Timestamp.from(fix.recordedAt()), Timestamp.from(receivedAt), fix.latitude(), fix.longitude(),
            fix.accuracyM(), fix.headingDeg(), fix.speedKmh(), lowQuality, commandId)
        == 1;
  }

  /** The trip's service date, when the trip is this vehicle's and visible to the actor. */
  public LocalDate tripDate(UUID tripId, String vehicleId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT service_date FROM execution.trips WHERE trip_id = ? AND vehicle_id = ?", tripId, vehicleId);
    return row == null ? null : ((Date) row.get("service_date")).toLocalDate();
  }

  /** Recent good fixes of the depot's vehicles, grouped by vehicle, newest first within each. */
  public List<Map<String, Object>> latestOfDepot(String depotCode, LocalDate serviceDate) {
    return database.query(
        RECENT.formatted("p.depot_code = ? AND p.service_date = ?", RECENT_FIXES),
        depotCode, Date.valueOf(serviceDate));
  }

  /** Vehicles still on their way to the outlet: a trip with a pending or arrived stop there. */
  public List<Map<String, Object>> latestForOutlet(String outletId, LocalDate serviceDate) {
    return database.query(
        RECENT.formatted(
            """
            p.service_date = ?
                 AND p.trip_id IN (SELECT d.trip_id FROM execution.delivery_records d
                                    WHERE d.outlet_id = ? AND d.service_date = ?
                                      AND d.outcome IN ('pending', 'arrived'))""",
            RECENT_FIXES),
        Date.valueOf(serviceDate), outletId, Date.valueOf(serviceDate));
  }

  /** One trip's points after a keyset position, oldest first. */
  public List<Map<String, Object>> trail(UUID tripId, Instant after, int limit) {
    return database.query(
        """
        SELECT recorded_at, latitude, longitude, low_quality
          FROM execution.vehicle_positions
         WHERE trip_id = ? AND recorded_at > ?
         ORDER BY recorded_at
         LIMIT ?
        """,
        tripId, Timestamp.from(after == null ? Instant.EPOCH : after), limit);
  }

  /** Whether the actor may see the trip at all: a driver or dispatcher sees it, a store sees its stops. */
  public boolean tripVisible(UUID tripId) {
    return Boolean.TRUE.equals(
        database
            .queryOne(
                """
                SELECT EXISTS (SELECT 1 FROM execution.trips WHERE trip_id = ?)
                    OR EXISTS (SELECT 1 FROM execution.delivery_records WHERE trip_id = ?) AS visible
                """,
                tripId, tripId)
            .get("visible"));
  }

  /** R-EXE-21, through the definer function; the execution role holds no DELETE. */
  public int thin(LocalDate before, int limit) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT execution.thin_vehicle_positions(?, ?) AS removed", Date.valueOf(before), limit);
    return ((Number) row.get("removed")).intValue();
  }
}
