package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.domain.DeliveryLines;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.ProofOfDelivery;
import com.waypoint.dispatch.execution.domain.ServiceWindow;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Execution's writes, and the load of one delivery record for a command.
 *
 * <p>Never opens a transaction: the command bus or the relay already did, as
 * waypoint_execution, with the actor set for row-level security. A record
 * outside the actor's scope is simply not found here.
 */
@Repository
public class JdbcDeliveryRepository {
  private static final String COLUMNS =
      """
      delivery_id, trip_id, order_id, outlet_id, depot_code, vehicle_id, service_date, stop_sequence,
      item_count, planned_arrival, window_open, window_close, mall_outlet, outcome, started_at,
      arrived_at, service_started_at, completed_at, wait_minutes, late_minutes, late_reason,
      timing_uncertain, delivered_units, failure_reason, disposition_note, low_evidence, proof_id,
      row_version
      """;

  private final Database database;

  public JdbcDeliveryRepository(Database database) {
    this.database = database;
  }

  /** A trip as trip.released described it. */
  public record TripRow(
      UUID tripId, UUID planId, int planVersion, String depotCode, String vehicleId, LocalDate serviceDate) {}

  /** Who recorded a step, on what, and what the device's clock said. */
  public record Stamp(UUID actorId, UUID deviceId, Instant serverAt, Instant clientAt) {}

  // ---- building the run sheet ------------------------------------------------

  /** @return false when the trip was already there, which makes a redelivered release a no-op */
  public boolean insertTrip(TripRow trip, Instant releasedAt) {
    return database.update(
            """
            INSERT INTO execution.trips (trip_id, plan_id, plan_version, depot_code, vehicle_id, service_date, released_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_id) DO NOTHING
            """,
            trip.tripId(), trip.planId(), trip.planVersion(), trip.depotCode(), trip.vehicleId(),
            Date.valueOf(trip.serviceDate()), Timestamp.from(releasedAt))
        == 1;
  }

  /**
   * @param tripStops how many distinct stops the trip has, copied onto every record of it so a
   *     store manager, who sees only its own stop, can still read "stop 3 of 7"
   * @return false when the trip already had a record for this order
   */
  public boolean insertRecord(DeliveryRecord r, int tripStops, Instant at) {
    return database.update(
        """
        INSERT INTO execution.delivery_records
            (delivery_id, trip_id, order_id, outlet_id, depot_code, vehicle_id, service_date, stop_sequence,
             item_count, planned_arrival, window_open, window_close, mall_outlet, released_at,
             server_recorded_at, trip_stop_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (trip_id, order_id) DO NOTHING
        """,
        r.deliveryId(), r.tripId(), r.orderId(), r.outletId(), r.depotCode(), r.vehicleId(),
        Date.valueOf(r.serviceDate()), r.sequence(), r.itemCount(), Time.valueOf(r.plannedArrival()),
        Time.valueOf(r.window().open()), Time.valueOf(r.window().close()), r.mallOutlet(),
        Timestamp.from(at), Timestamp.from(at), tripStops)
        == 1;
  }

  // ---- proof retention (P-14) ----------------------------------------------

  public record ExpiredAttachment(UUID attachmentId, String storageKey) {}

  /** Artifacts past their retention whose bytes are still held, oldest first. */
  public List<ExpiredAttachment> expiredAttachments(LocalDate today, int limit) {
    return database
        .query(
            "SELECT attachment_id, storage_key FROM execution.attachments"
                + " WHERE retain_until < ? AND purged_at IS NULL ORDER BY retain_until LIMIT ?",
            Date.valueOf(today), limit)
        .stream()
        .map(row -> new ExpiredAttachment((UUID) row.get("attachment_id"), (String) row.get("storage_key")))
        .toList();
  }

  public void markPurged(UUID attachmentId, Instant at) {
    database.update(
        "UPDATE execution.attachments SET purged_at = ? WHERE attachment_id = ? AND purged_at IS NULL",
        Timestamp.from(at), attachmentId);
  }

  /** The artifact's bytes are still held: stored, and not cleared past retention. */
  public boolean isHeld(UUID attachmentId) {
    return !database
        .query(
            "SELECT 1 FROM execution.attachments WHERE attachment_id = ? AND purged_at IS NULL", attachmentId)
        .isEmpty();
  }

  /** Bytes of evidence still held. */
  public long heldAttachmentBytes() {
    Map<String, Object> row =
        database.queryOne(
            "SELECT coalesce(sum(size_bytes), 0) AS bytes FROM execution.attachments WHERE purged_at IS NULL");
    return ((Number) row.get("bytes")).longValue();
  }

  /** The order's products, copied at release beside its unit count. */
  public void insertLines(UUID deliveryId, List<DeliveryLines.Ordered> lines) {
    for (DeliveryLines.Ordered line : lines) {
      database.update(
          """
          INSERT INTO execution.delivery_lines (delivery_id, product_id, ordered_units)
          VALUES (?, ?, ?)
          ON CONFLICT (delivery_id, product_id) DO NOTHING
          """,
          deliveryId, line.productId(), line.units());
    }
  }

  // ---- loading for a command -------------------------------------------------

  public List<DeliveryLines.Ordered> orderedLines(UUID deliveryId) {
    return database
        .query(
            "SELECT product_id, ordered_units FROM execution.delivery_lines"
                + " WHERE delivery_id = ? ORDER BY product_id",
            deliveryId)
        .stream()
        .map(row -> new DeliveryLines.Ordered(
            (String) row.get("product_id"), ((Number) row.get("ordered_units")).intValue()))
        .toList();
  }

  /** What arrived of each product. In the command's transaction, after {@link #save}. */
  public void recordDeliveredLines(UUID deliveryId, List<DeliveryLines.Delivered> lines) {
    for (DeliveryLines.Delivered line : lines) {
      database.updateExpectingOneRow(
          "UPDATE execution.delivery_lines SET delivered_units = ? WHERE delivery_id = ? AND product_id = ?",
          line.units(), deliveryId, line.productId());
    }
  }

  public Optional<DeliveryRecord> find(UUID deliveryId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT " + COLUMNS + " FROM execution.delivery_records WHERE delivery_id = ?", deliveryId);
    return Optional.ofNullable(row).map(JdbcDeliveryRepository::record);
  }

  /** Stops of a trip nobody has reached yet, in order. */
  public List<DeliveryRecord> pendingOfTrip(UUID tripId) {
    return database
        .query(
            "SELECT " + COLUMNS + " FROM execution.delivery_records"
                + " WHERE trip_id = ? AND outcome = 'pending' ORDER BY stop_sequence",
            tripId)
        .stream()
        .map(JdbcDeliveryRepository::record)
        .toList();
  }

  /** Stops nobody has reached on the released trips of one depot and day. */
  public List<DeliveryRecord> pendingOfDay(String depotCode, LocalDate serviceDate) {
    return database
        .query(
            "SELECT " + COLUMNS + " FROM execution.delivery_records"
                + " WHERE depot_code = ? AND service_date = ? AND outcome = 'pending'"
                + " ORDER BY trip_id, stop_sequence",
            depotCode, Date.valueOf(serviceDate))
        .stream()
        .map(JdbcDeliveryRepository::record)
        .toList();
  }

  /** Whether the actor is the driver of this vehicle on this date, or the process itself. */
  public boolean mayRecordFor(String vehicleId, LocalDate serviceDate) {
    return Boolean.TRUE.equals(
        database
            .queryOne(
                "SELECT (app.actor_is_system() OR app.actor_drives(?, ?)) AS allowed",
                vehicleId, Date.valueOf(serviceDate))
            .get("allowed"));
  }

  // ---- writing a step --------------------------------------------------------

  /**
   * Writes the record's new state under the version guard. Zero rows means
   * another device got there first, which is a conflict and never a merge
   * (EXE-14).
   *
   * @return the new row version
   */
  public long save(DeliveryRecord next, long expectedVersion, Stamp stamp) {
    database.updateExpectingOneRow(
        """
        UPDATE execution.delivery_records
           SET outcome = ?, started_at = ?, arrived_at = ?, service_started_at = ?, completed_at = ?,
               wait_minutes = ?, late_minutes = ?, late_reason = ?, timing_uncertain = ?,
               delivered_units = ?, failure_reason = ?, disposition_note = ?, low_evidence = ?, proof_id = ?,
               client_recorded_at = ?, server_recorded_at = ?, recorded_by = ?, device_id = ?,
               row_version = row_version + 1
         WHERE delivery_id = ? AND row_version = ?
        """,
        next.outcome().name().toLowerCase(Locale.ROOT),
        timestamp(next.startedAt()), timestamp(next.arrivedAt()), timestamp(next.serviceStartedAt()),
        timestamp(next.completedAt()),
        next.waitMinutes().orElse(null), next.lateMinutes().orElse(null), next.lateReason().orElse(null),
        next.timingUncertain(),
        next.deliveredUnits().orElse(null), next.failureReason().orElse(null),
        next.dispositionNote().orElse(null), next.lowEvidence(), next.proofId().orElse(null),
        stamp.clientAt() == null ? null : Timestamp.from(stamp.clientAt()),
        Timestamp.from(stamp.serverAt()), stamp.actorId(), stamp.deviceId(),
        next.deliveryId(), expectedVersion);
    return expectedVersion + 1;
  }

  /** The device's own reading of the arrival time, kept beside the server's. */
  public void rememberDeviceArrival(UUID deliveryId, Instant deviceArrivedAt) {
    database.update(
        "UPDATE execution.delivery_records SET client_arrived_at = ? WHERE delivery_id = ?",
        Timestamp.from(deviceArrivedAt), deliveryId);
  }

  /** The expected arrival moved; this is a projection of the trip's delay, not a step of the stop. */
  public void expect(UUID deliveryId, Instant expectedArrival) {
    database.update(
        "UPDATE execution.delivery_records SET expected_arrival = ? WHERE delivery_id = ?",
        Timestamp.from(expectedArrival), deliveryId);
  }

  public int announcedDelay(UUID tripId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT announced_delay_minutes FROM execution.trips WHERE trip_id = ? FOR UPDATE", tripId);
    return row == null ? 0 : ((Number) row.get("announced_delay_minutes")).intValue();
  }

  public void announceDelay(UUID tripId, int minutes) {
    database.update(
        "UPDATE execution.trips SET announced_delay_minutes = ? WHERE trip_id = ?", minutes, tripId);
  }

  // ---- proof -----------------------------------------------------------------

  /** One row per capture; the highest attempt is the delivery's proof. */
  public void appendProof(
      UUID proofId, UUID deliveryId, ProofOfDelivery proof, UUID commandId, Stamp stamp) {
    database.update(
        """
        INSERT INTO execution.proofs
            (proof_id, delivery_id, attempt, photo_attachment_id, signature_attachment_id, recipient_name,
             fallback_reason, low_evidence, captured_by, device_id, command_id, captured_at, client_recorded_at)
        SELECT ?, ?, coalesce(max(attempt), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
          FROM execution.proofs WHERE delivery_id = ?
        """,
        proofId, deliveryId, proof.photoAttachmentId().orElse(null),
        proof.signatureAttachmentId().orElse(null), proof.recipientName().orElse(null),
        proof.fallbackReason().orElse(null), proof.lowEvidence(), stamp.actorId(), stamp.deviceId(),
        commandId, Timestamp.from(stamp.serverAt()),
        stamp.clientAt() == null ? null : Timestamp.from(stamp.clientAt()),
        deliveryId);
  }

  /** What is already stored under an attachment id, whichever delivery it belongs to. */
  public record StoredAttachment(
      UUID deliveryId, String kind, String sha256, String contentType, String storageKey) {}

  public Optional<StoredAttachment> attachment(UUID attachmentId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT delivery_id, kind, sha256, content_type, storage_key FROM execution.attachments"
                + " WHERE attachment_id = ?",
            attachmentId);
    return Optional.ofNullable(row)
        .map(r -> new StoredAttachment(
            (UUID) r.get("delivery_id"), (String) r.get("kind"), (String) r.get("sha256"),
            (String) r.get("content_type"),
            (String) r.get("storage_key")));
  }

  /** @return false when the id was already taken, by this delivery or one the actor cannot see */
  public boolean insertAttachment(
      UUID attachmentId, UUID deliveryId, String kind, String contentType, int sizeBytes, String sha256,
      String storageKey, LocalDate retainUntil, UUID uploadedBy, Instant at) {
    return database.update(
            """
            INSERT INTO execution.attachments
                (attachment_id, delivery_id, kind, content_type, size_bytes, sha256, storage_key, retain_until,
                 uploaded_by, uploaded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (attachment_id) DO NOTHING
            """,
            attachmentId, deliveryId, kind, contentType, sizeBytes, sha256, storageKey,
            Date.valueOf(retainUntil), uploadedBy, Timestamp.from(at))
        == 1;
  }

  // ---- reports ---------------------------------------------------------------

  /**
   * Moves the stop's version on without touching what was recorded on it, for a
   * decision kept beside the stop: the phone counts one version per command.
   */
  public long touch(UUID deliveryId, long expectedVersion) {
    database.updateExpectingOneRow(
        "UPDATE execution.delivery_records SET row_version = row_version + 1 WHERE delivery_id = ? AND row_version = ?",
        deliveryId, expectedVersion);
    return expectedVersion + 1;
  }

  /** @return false when the stop already has a decision: one per stop, never rewritten */
  public boolean insertStoreAnswerWaiver(UUID deliveryId, String reason, UUID commandId, Stamp stamp) {
    return database.update(
            """
            INSERT INTO execution.store_answer_waivers
                (delivery_id, reason, decided_by, device_id, command_id, decided_at, client_recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (delivery_id) DO NOTHING
            """,
            deliveryId, reason, stamp.actorId(), stamp.deviceId(), commandId, Timestamp.from(stamp.serverAt()),
            stamp.clientAt() == null ? null : Timestamp.from(stamp.clientAt()))
        == 1;
  }

  public void insertVehicleReport(
      UUID reportId, String vehicleId, String depotCode, LocalDate serviceDate, String kind, String status,
      String note, UUID deliveryId, UUID commandId, Stamp stamp) {
    database.update(
        """
        INSERT INTO execution.vehicle_reports
            (report_id, vehicle_id, depot_code, service_date, kind, status, note, delivery_id, reported_by,
             device_id, command_id, reported_at, client_recorded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        reportId, vehicleId, depotCode, Date.valueOf(serviceDate), kind, status, note, deliveryId,
        stamp.actorId(), stamp.deviceId(), commandId, Timestamp.from(stamp.serverAt()),
        stamp.clientAt() == null ? null : Timestamp.from(stamp.clientAt()));
  }

  public void insertRoadReport(
      UUID reportId, String vehicleId, String depotCode, LocalDate serviceDate, String districtName,
      String description, UUID deliveryId, UUID commandId, Stamp stamp) {
    database.update(
        """
        INSERT INTO execution.road_reports
            (report_id, vehicle_id, depot_code, service_date, district_name, description, delivery_id,
             reported_by, device_id, command_id, reported_at, client_recorded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        reportId, vehicleId, depotCode, Date.valueOf(serviceDate), districtName, description, deliveryId,
        stamp.actorId(), stamp.deviceId(), commandId, Timestamp.from(stamp.serverAt()),
        stamp.clientAt() == null ? null : Timestamp.from(stamp.clientAt()));
  }

  // ---- mapping ---------------------------------------------------------------

  public static DeliveryRecord record(Map<String, Object> row) {
    return new DeliveryRecord(
        (UUID) row.get("delivery_id"),
        (UUID) row.get("trip_id"),
        (UUID) row.get("order_id"),
        (String) row.get("outlet_id"),
        (String) row.get("depot_code"),
        (String) row.get("vehicle_id"),
        ((Date) row.get("service_date")).toLocalDate(),
        ((Number) row.get("stop_sequence")).intValue(),
        ((Number) row.get("item_count")).intValue(),
        ((Time) row.get("planned_arrival")).toLocalTime(),
        new ServiceWindow(
            ((Time) row.get("window_open")).toLocalTime(), ((Time) row.get("window_close")).toLocalTime()),
        Boolean.TRUE.equals(row.get("mall_outlet")),
        DeliveryOutcome.valueOf(((String) row.get("outcome")).toUpperCase(Locale.ROOT)),
        instant(row.get("started_at")),
        instant(row.get("arrived_at")),
        instant(row.get("service_started_at")),
        instant(row.get("completed_at")),
        integer(row.get("wait_minutes")),
        integer(row.get("late_minutes")),
        Optional.ofNullable((String) row.get("late_reason")),
        Boolean.TRUE.equals(row.get("timing_uncertain")),
        integer(row.get("delivered_units")),
        Optional.ofNullable((String) row.get("failure_reason")),
        Optional.ofNullable((String) row.get("disposition_note")),
        Boolean.TRUE.equals(row.get("low_evidence")),
        Optional.ofNullable((UUID) row.get("proof_id")),
        ((Number) row.get("row_version")).longValue());
  }

  static Optional<Instant> instant(Object value) {
    return Optional.ofNullable((Timestamp) value).map(Timestamp::toInstant);
  }

  static Optional<Integer> integer(Object value) {
    return Optional.ofNullable((Number) value).map(Number::intValue);
  }

  private static Timestamp timestamp(Optional<Instant> value) {
    return value.map(Timestamp::from).orElse(null);
  }
}
