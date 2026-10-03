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
  /** Last good fix per vehicle, and whether its trip still has stops ahead. */
  private static final String LATEST =
      """
      SELECT DISTINCT ON (p.vehicle_id)
             p.vehicle_id, p.trip_id, p.latitude, p.longitude, p.heading_deg, p.accuracy_m, p.recorded_at,
             EXISTS (SELECT 1 FROM execution.delivery_records d
                      WHERE d.vehicle_id = p.vehicle_id AND d.service_date = p.service_date
                        AND d.outcome IN ('pending', 'arrived')) AS in_progress
        FROM execution.vehicle_positions p
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

  public List<Map<String, Object>> latestOfDepot(String depotCode, LocalDate serviceDate) {
    return database.query(
        LATEST
            + " WHERE p.depot_code = ? AND p.service_date = ? AND NOT p.low_quality"
            + " ORDER BY p.vehicle_id, p.recorded_at DESC",
        depotCode, Date.valueOf(serviceDate));
  }

  /** Vehicles still on their way to the outlet: a trip with a pending or arrived stop there. */
  public List<Map<String, Object>> latestForOutlet(String outletId, LocalDate serviceDate) {
    return database.query(
        LATEST
            + """
               WHERE p.service_date = ? AND NOT p.low_quality
                 AND p.trip_id IN (SELECT d.trip_id FROM execution.delivery_records d
                                    WHERE d.outlet_id = ? AND d.service_date = ?
                                      AND d.outcome IN ('pending', 'arrived'))
               ORDER BY p.vehicle_id, p.recorded_at DESC
              """,
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
