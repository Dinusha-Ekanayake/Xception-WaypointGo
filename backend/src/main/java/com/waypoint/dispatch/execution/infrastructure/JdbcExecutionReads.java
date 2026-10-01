package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Execution's reads, as rows. Row-level security has already decided which
 * rows exist for the actor; nothing here filters by who is asking.
 */
@Repository
public class JdbcExecutionReads {
  private static final String RECORD =
      """
      SELECT d.delivery_id, d.trip_id, d.order_id, d.outlet_id, d.depot_code, d.vehicle_id, d.service_date,
             d.stop_sequence, d.item_count, d.planned_arrival, d.window_open, d.window_close, d.mall_outlet,
             d.outcome, d.expected_arrival, d.started_at, d.arrived_at, d.service_started_at, d.completed_at,
             d.wait_minutes, d.late_minutes, d.late_reason, d.timing_uncertain, d.delivered_units,
             d.failure_reason, d.disposition_note, d.low_evidence, d.proof_id, d.client_recorded_at,
             d.server_recorded_at, d.row_version
        FROM execution.delivery_records d
      """;

  private final Database database;

  public JdbcExecutionReads(Database database) {
    this.database = database;
  }

  /** One vehicle's stops for a day, trips in the order they left, stops in delivery order. */
  public List<Map<String, Object>> stopsOfVehicle(String vehicleId, LocalDate serviceDate) {
    return database.query(
        RECORD + " WHERE d.vehicle_id = ? AND d.service_date = ? ORDER BY d.released_at, d.trip_id, d.stop_sequence",
        vehicleId, Date.valueOf(serviceDate));
  }

  public List<Map<String, Object>> stopsOfDepot(String depotCode, LocalDate serviceDate) {
    return database.query(
        RECORD + " WHERE d.depot_code = ? AND d.service_date = ?"
            + " ORDER BY d.vehicle_id, d.released_at, d.trip_id, d.stop_sequence",
        depotCode, Date.valueOf(serviceDate));
  }

  public List<Map<String, Object>> stopsOfOutlet(String outletId, LocalDate serviceDate) {
    return database.query(
        RECORD + " WHERE d.outlet_id = ? AND d.service_date = ? ORDER BY d.released_at, d.stop_sequence",
        outletId, Date.valueOf(serviceDate));
  }

  /** The vehicles the actor is assigned to drive on a date. Row-level security shows only their own. */
  public List<String> vehiclesDrivenOn(LocalDate serviceDate) {
    return database
        .query(
            "SELECT DISTINCT vehicle_id FROM iam.vehicle_driver_assignments"
                + " WHERE driver_user_id = app.current_actor() AND validity @> ?::date ORDER BY vehicle_id",
            Date.valueOf(serviceDate))
        .stream()
        .map(row -> (String) row.get("vehicle_id"))
        .toList();
  }

  public Map<String, Object> record(UUID deliveryId) {
    return database.queryOne(RECORD + " WHERE d.delivery_id = ?", deliveryId);
  }

  /** The latest attempt at an order: a redelivery is a later record for the same order. */
  public Map<String, Object> latestForOrder(UUID orderId) {
    return database.queryOne(
        RECORD + " WHERE d.order_id = ? ORDER BY d.released_at DESC, d.server_recorded_at DESC LIMIT 1",
        orderId);
  }

  /** The delivery's current proof, with where each artifact is stored. */
  public Map<String, Object> proof(UUID deliveryId) {
    return database.queryOne(
        """
        SELECT p.proof_id, p.delivery_id, p.recipient_name, p.fallback_reason, p.low_evidence, p.captured_at,
               p.photo_attachment_id, p.signature_attachment_id
          FROM execution.delivery_records d
          JOIN execution.proofs p ON p.proof_id = d.proof_id
         WHERE d.delivery_id = ?
        """,
        deliveryId);
  }

  /** What to serve for an attachment. Called for a signed link, which is its own authorization. */
  public Map<String, Object> attachmentContent(UUID attachmentId) {
    return database.queryOne(
        "SELECT storage_key, content_type, scan_status FROM execution.attachments WHERE attachment_id = ?",
        attachmentId);
  }

  public boolean depotInScope(String depotCode) {
    return Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok"));
  }

  public boolean outletInScope(String outletId) {
    return Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_has_outlet(?) AS ok", outletId).get("ok"));
  }
}
