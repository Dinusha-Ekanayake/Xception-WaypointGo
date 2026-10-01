package com.waypoint.dispatch.loading.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Writes the copy of a published trip that Loading works from. Runs inside the
 * relay's transaction as the system actor. Every write is safe to repeat,
 * because delivery is at least once.
 */
@Repository
public class JdbcManifestWriter {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Database database;

  public JdbcManifestWriter(Database database) {
    this.database = database;
  }

  /** Serialize the one demonstration plan per depot and day. */
  public boolean beginFixtureIfAbsent(String depotCode, LocalDate serviceDate) {
    String key = "loading-fixture:" + depotCode + ":" + serviceDate;
    database.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
    return database.queryOne(
            "SELECT 1 AS found FROM loading.trips WHERE depot_code = ? AND service_date = ? LIMIT 1",
            depotCode, Date.valueOf(serviceDate))
        == null;
  }

  public record TripRow(
      UUID tripId,
      int planVersion,
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      String vehicleId,
      int tripNumber,
      int tripsForVehicle,
      String brandCode,
      String districtName,
      String temperature,
      LocalTime plannedDeparture,
      String dockCode,
      BigDecimal weightCapKg,
      BigDecimal volumeCapM3) {}

  public record StopRow(
      UUID orderId,
      int stopSequence,
      String orderRef,
      String outletId,
      String temperature,
      int itemCount,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      LocalTime plannedArrival) {}

  public boolean versionExists(UUID tripId, int planVersion) {
    return database.queryOne(
            "SELECT 1 AS found FROM loading.trips WHERE trip_id = ? AND plan_version = ?",
            tripId,
            planVersion)
        != null;
  }

  /** The current plan version of a trip, if Loading has one. */
  public Optional<Integer> currentVersion(UUID tripId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT plan_version FROM loading.trips WHERE trip_id = ? AND superseded_at IS NULL", tripId);
    return row == null ? Optional.empty() : Optional.of(((Number) row.get("plan_version")).intValue());
  }

  public void insertTrip(TripRow t, Instant at) {
    database.update(
        """
        INSERT INTO loading.trips
            (trip_id, plan_version, plan_id, depot_code, service_date, vehicle_id, trip_number,
             trips_for_vehicle, brand_code, district_name, temperature, planned_departure, dock_code,
             weight_cap_kg, volume_cap_m3, received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        t.tripId(), t.planVersion(), t.planId(), t.depotCode(), Date.valueOf(t.serviceDate()),
        t.vehicleId(), t.tripNumber(), t.tripsForVehicle(), t.brandCode(), t.districtName(),
        t.temperature(), Time.valueOf(t.plannedDeparture()), t.dockCode(), t.weightCapKg(),
        t.volumeCapM3(), Timestamp.from(at));
  }

  public void insertStop(UUID tripId, int planVersion, StopRow s) {
    database.update(
        """
        INSERT INTO loading.stops
            (trip_id, plan_version, order_id, stop_sequence, order_ref, outlet_id, temperature,
             item_count, weight_kg, volume_m3, planned_arrival)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tripId, planVersion, s.orderId(), s.stopSequence(), s.orderRef(), s.outletId(),
        s.temperature(), s.itemCount(), s.weightKg(), s.volumeM3(),
        s.plannedArrival() == null ? null : Time.valueOf(s.plannedArrival()));
  }

  public void insertItem(UUID tripId, int planVersion, UUID orderId, int lineNo, String productId, int units) {
    database.update(
        """
        INSERT INTO loading.items (trip_id, plan_version, order_id, line_no, product_id, units)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        tripId, planVersion, orderId, lineNo, productId, units);
  }

  /** A trip seen for the first time starts not started, at version 1. */
  public void insertSession(UUID tripId, String depotCode, int planVersion, Instant at) {
    database.update(
        """
        INSERT INTO loading.sessions (trip_id, depot_code, plan_version, status, row_version, updated_at)
        VALUES (?, ?, ?, 'not_started', 1, ?)
        ON CONFLICT (trip_id) DO NOTHING
        """,
        tripId, depotCode, planVersion, Timestamp.from(at));
  }

  /**
   * A revision supersedes the old copy and moves an active session on by one.
   * A released manifest is historical and cannot be replaced by a late plan.
   *
   * @return true when this trip was active and superseded
   */
  public boolean supersede(UUID tripId, int oldVersion, int newVersion, Instant at) {
    int changed = database.update(
        """
        UPDATE loading.trips t SET superseded_at = ?
         WHERE t.trip_id = ? AND t.plan_version = ? AND t.superseded_at IS NULL
           AND EXISTS (SELECT 1 FROM loading.sessions s
                        WHERE s.trip_id = t.trip_id AND s.status <> 'released')
        """,
        Timestamp.from(at), tripId, oldVersion);
    if (changed != 1) {
      return false;
    }
    database.updateExpectingOneRow(
        """
        UPDATE loading.sessions
           SET plan_version = ?, row_version = row_version + 1, updated_at = ?
         WHERE trip_id = ? AND status <> 'released'
        """,
        newVersion, Timestamp.from(at), tripId);
    return true;
  }

  /**
   * Retire active trips omitted by the new version of a plan. Released trips
   * remain visible as historical driver run sheets. Retiring one invalidates
   * its session version and clears its holder; no current manifest remains.
   */
  public int retireOmittedTrips(
      UUID planId, Optional<UUID> supersedesPlanId, java.util.Set<UUID> retainedTripIds, Instant at) {
    List<UUID> active = database.query(
        """
        SELECT t.trip_id FROM loading.trips t
        JOIN loading.sessions s ON s.trip_id = t.trip_id
        WHERE (t.plan_id = ? OR (CAST(? AS uuid) IS NOT NULL AND t.plan_id = CAST(? AS uuid)))
          AND t.superseded_at IS NULL AND s.status <> 'released'
        ORDER BY t.trip_id
        """,
        planId, supersedesPlanId.orElse(null), supersedesPlanId.orElse(null))
        .stream().map(row -> (UUID) row.get("trip_id")).toList();
    int retired = 0;
    for (UUID tripId : active) {
      if (retainedTripIds.contains(tripId)) {
        continue;
      }
      Integer current = currentVersion(tripId).orElse(null);
      if (current == null) {
        continue;
      }
      int changed = database.update(
          "UPDATE loading.trips SET superseded_at = ? WHERE trip_id = ? AND plan_version = ? AND superseded_at IS NULL",
          Timestamp.from(at), tripId, current);
      if (changed == 0) {
        continue;
      }
      database.updateExpectingOneRow(
          """
          UPDATE loading.sessions
             SET row_version = row_version + 1, holder_user_id = NULL, holder_name = NULL,
                 holder_code = NULL, held_since = NULL, updated_at = ?
           WHERE trip_id = ? AND status <> 'released'
          """,
          Timestamp.from(at), tripId);
      retired++;
    }
    return retired;
  }

  /**
   * Carries the latest check of every item line that is identical in both
   * versions: same vehicle, temperature, stop position and complete order.
   * A vehicle swap requires loading the replacement, and a stop reorder requires
   * checking the physical load order again. Any changed order starts unchecked.
   *
   * @return lines carried
   */
  public int carryUnchangedChecks(UUID tripId, int oldVersion, int newVersion, Instant at) {
    List<Map<String, Object>> carried = database.query(
        """
        SELECT ni.order_id, ni.line_no, c.status, c.units,
               coalesce(c.reason, 'carried from plan version ' || ?) AS reason,
               c.shortfall_id, c.actor_user_id, c.device_id, c.command_id, c.recorded_at,
               c.client_recorded_at
        FROM loading.items ni
        JOIN loading.items oi
          ON oi.trip_id = ni.trip_id AND oi.plan_version = ? AND oi.order_id = ni.order_id
         AND oi.line_no = ni.line_no AND oi.product_id = ni.product_id AND oi.units = ni.units
        JOIN loading.trips nt ON nt.trip_id = ni.trip_id AND nt.plan_version = ni.plan_version
        JOIN loading.trips ot ON ot.trip_id = oi.trip_id AND ot.plan_version = oi.plan_version
          AND ot.vehicle_id = nt.vehicle_id AND ot.temperature = nt.temperature
        JOIN loading.stops ns ON ns.trip_id = ni.trip_id AND ns.plan_version = ni.plan_version
          AND ns.order_id = ni.order_id
        JOIN loading.stops os ON os.trip_id = oi.trip_id AND os.plan_version = oi.plan_version
          AND os.order_id = oi.order_id AND os.stop_sequence = ns.stop_sequence
          AND os.outlet_id = ns.outlet_id AND os.temperature = ns.temperature
          AND os.item_count = ns.item_count AND os.weight_kg = ns.weight_kg AND os.volume_m3 = ns.volume_m3
        JOIN LATERAL (
            SELECT * FROM loading.item_checks ic
            WHERE ic.trip_id = oi.trip_id AND ic.plan_version = oi.plan_version
              AND ic.order_id = oi.order_id AND ic.line_no = oi.line_no
            ORDER BY ic.attempt DESC LIMIT 1) c ON c.status <> 'pending'
        WHERE ni.trip_id = ? AND ni.plan_version = ?
          AND NOT EXISTS (
            SELECT line_no, product_id, units FROM loading.items
            WHERE trip_id = oi.trip_id AND plan_version = oi.plan_version AND order_id = oi.order_id
            EXCEPT
            SELECT line_no, product_id, units FROM loading.items
            WHERE trip_id = ni.trip_id AND plan_version = ni.plan_version AND order_id = ni.order_id)
          AND NOT EXISTS (
            SELECT line_no, product_id, units FROM loading.items
            WHERE trip_id = ni.trip_id AND plan_version = ni.plan_version AND order_id = ni.order_id
            EXCEPT
            SELECT line_no, product_id, units FROM loading.items
            WHERE trip_id = oi.trip_id AND plan_version = oi.plan_version AND order_id = oi.order_id)
        """,
        oldVersion, oldVersion, tripId, newVersion);
    for (Map<String, Object> check : carried) {
      database.update(
          """
          INSERT INTO loading.item_checks
              (check_id, trip_id, plan_version, order_id, line_no, attempt, status, units, reason,
               shortfall_id, actor_user_id, device_id, command_id, recorded_at, client_recorded_at)
          VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          UuidV7.generate(at, RANDOM), tripId, newVersion, check.get("order_id"), check.get("line_no"),
          check.get("status"), check.get("units"), check.get("reason"), check.get("shortfall_id"),
          check.get("actor_user_id"), check.get("device_id"), check.get("command_id"),
          check.get("recorded_at"), check.get("client_recorded_at"));
    }
    return carried.size();
  }

  /** Issues resolved the shortfalls of an order on a trip (shortfall.resolved names no shortfall id). */
  public int resolveShortfalls(UUID tripId, UUID orderId, String resolution, Instant at) {
    return database.update(
        """
        UPDATE loading.shortfalls SET resolved_at = ?, resolution = ?
         WHERE trip_id = ? AND order_id = ? AND resolved_at IS NULL
        """,
        Timestamp.from(at), resolution, tripId, orderId);
  }
}
