package com.waypoint.dispatch.planning.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** Trips whose truck has left (R-PLN-44), learned from trip.released. Insert only. */
@Repository
public class JdbcReleasedTrips {
  private final Database database;

  public JdbcReleasedTrips(Database database) {
    this.database = database;
  }

  /** Records a release once; a redelivered event changes nothing. */
  public void record(UUID tripId, UUID planId, String depotCode, LocalDate serviceDate, String vehicleId, Instant releasedAt) {
    database.update(
        """
        INSERT INTO planning.released_trips (trip_id, plan_id, depot_code, service_date, vehicle_id, released_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (trip_id) DO NOTHING
        """,
        tripId, planId, depotCode, Date.valueOf(serviceDate), vehicleId, Timestamp.from(releasedAt));
  }

  /** A vehicle's trips of the day that have left the depot: trip id and the plan it left under. */
  public java.util.List<java.util.Map<String, Object>> ofVehicle(String depotCode, LocalDate serviceDate, String vehicleId) {
    return database.query(
        "SELECT trip_id, plan_id FROM planning.released_trips WHERE depot_code = ? AND service_date = ? AND vehicle_id = ?",
        depotCode, Date.valueOf(serviceDate), vehicleId);
  }

  /** The vehicles that have left the depot with a trip of the day (R-PLN-44). */
  public Set<String> vehiclesForDay(String depotCode, LocalDate serviceDate) {
    return database.query(
            "SELECT DISTINCT vehicle_id FROM planning.released_trips WHERE depot_code = ? AND service_date = ?",
            depotCode, Date.valueOf(serviceDate))
        .stream()
        .map(r -> (String) r.get("vehicle_id"))
        .collect(Collectors.toSet());
  }

  public Set<UUID> forDay(String depotCode, LocalDate serviceDate) {
    return database.query(
            "SELECT trip_id FROM planning.released_trips WHERE depot_code = ? AND service_date = ?",
            depotCode, Date.valueOf(serviceDate))
        .stream()
        .map(r -> (UUID) r.get("trip_id"))
        .collect(Collectors.toSet());
  }
}
