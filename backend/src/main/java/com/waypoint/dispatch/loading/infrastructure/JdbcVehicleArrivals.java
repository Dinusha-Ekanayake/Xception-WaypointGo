package com.waypoint.dispatch.loading.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * When each vehicle's driver reported at the depot (R-LOD-12), as the dock board
 * shows it. One row per vehicle and day: the first report is kept, so the event
 * delivered twice, or the driver saying so twice, changes nothing.
 */
@Repository
public class JdbcVehicleArrivals {
  private final Database database;

  public JdbcVehicleArrivals(Database database) {
    this.database = database;
  }

  /** Whether the vehicle's driver has reported at the depot for that day (R-LOD-13). */
  public boolean arrived(String vehicleId, LocalDate serviceDate) {
    return database.queryOne(
            "SELECT 1 FROM loading.vehicle_arrivals WHERE vehicle_id = ? AND service_date = ?",
            vehicleId, Date.valueOf(serviceDate))
        != null;
  }

  /** @return true when this is the vehicle's first report that day */
  public boolean record(String vehicleId, String depotCode, LocalDate serviceDate, UUID driverId, Instant arrivedAt) {
    return database.update(
            """
            INSERT INTO loading.vehicle_arrivals (vehicle_id, service_date, depot_code, driver_id, arrived_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (vehicle_id, service_date) DO NOTHING
            """,
            vehicleId, Date.valueOf(serviceDate), depotCode, driverId, Timestamp.from(arrivedAt))
        == 1;
  }
}
