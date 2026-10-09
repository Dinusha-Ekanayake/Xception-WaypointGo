package com.waypoint.dispatch.issues.infrastructure;

import com.waypoint.dispatch.issues.contract.IssueViews.DecisionView;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Questions asked of stores on live trips (FF-09). Never opens a transaction;
 * row-level security keeps them to the dispatcher who oversees the depot and to
 * the store asked.
 */
@Repository
public class JdbcDecisionRequests {
  private static final String COLUMNS =
      "request_id, kind, depot_code, service_date, trip_id, vehicle_id, delivery_id, outlet_id, about_delivery_id,"
          + " about_outlet_id, about_units, receive_at, arrival_at, deadline, state, answer, answered_via, answered_at,"
          + " answered_late, reason, voice_note_id, asked_at, row_version, asked_by";

  private final Database database;

  public JdbcDecisionRequests(Database database) {
    this.database = database;
  }

  /** Withdraws the open question of this kind about this stop; how many were withdrawn. */
  public int withdrawOpen(UUID deliveryId, String kind, Instant at) {
    return database.update(
        "UPDATE issues.decision_requests SET state = 'withdrawn', closed_at = ?, row_version = row_version + 1"
            + " WHERE delivery_id = ? AND kind = ? AND state = 'sent'",
        Timestamp.from(at), deliveryId, kind);
  }

  public void insert(
      UUID requestId, String kind, String depotCode, LocalDate serviceDate, UUID tripId, String vehicleId,
      UUID deliveryId, String outletId, Optional<UUID> aboutDeliveryId, Optional<String> aboutOutletId,
      Optional<Integer> aboutUnits, Optional<Instant> receiveAt, Optional<Instant> arrivalAt, Instant deadline,
      UUID askedBy, Instant askedAt, UUID commandId) {
    database.update(
        "INSERT INTO issues.decision_requests (request_id, kind, depot_code, service_date, trip_id, vehicle_id,"
            + " delivery_id, outlet_id, about_delivery_id, about_outlet_id, about_units, receive_at, arrival_at,"
            + " deadline, asked_by, asked_at, command_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        requestId, kind, depotCode, Date.valueOf(serviceDate), tripId, vehicleId, deliveryId, outletId,
        aboutDeliveryId.orElse(null), aboutOutletId.orElse(null), aboutUnits.orElse(null),
        receiveAt.map(Timestamp::from).orElse(null), arrivalAt.map(Timestamp::from).orElse(null),
        Timestamp.from(deadline), askedBy, Timestamp.from(askedAt), commandId);
  }

  public Optional<DecisionView> find(UUID requestId) {
    return Optional.ofNullable(
            database.queryOne("SELECT " + COLUMNS + " FROM issues.decision_requests WHERE request_id = ?", requestId))
        .map(JdbcDecisionRequests::view);
  }

  /** Whether this stop was ever asked this kind of question: the system asks once (R-ISS-15). */
  public boolean everAsked(UUID deliveryId, String kind) {
    return database.queryOne(
            "SELECT 1 AS one FROM issues.decision_requests WHERE delivery_id = ? AND kind = ? LIMIT 1", deliveryId, kind)
        != null;
  }

  /** Whether the actor may answer for this request's store: the store manager of the outlet. */
  public boolean answersFor(String outletId) {
    return Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_outlet(?) AS ok", outletId).get("ok"));
  }

  /** Whether the actor oversees the depot: the dispatcher, who asks and may record a phone answer. */
  public boolean oversees(String depotCode) {
    return Boolean.TRUE.equals(database.queryOne("SELECT app.actor_oversees_depot(?) AS ok", depotCode).get("ok"));
  }

  /** Records the answer at the version the caller saw; zero rows when it moved on. */
  public int answer(
      UUID requestId, long expectedVersion, String answer, String via, UUID by, Instant at, boolean late,
      Optional<String> reason, Optional<UUID> voiceNoteId) {
    return database.update(
        "UPDATE issues.decision_requests SET state = 'answered', answer = ?, answered_via = ?, answered_by = ?,"
            + " answered_at = ?, answered_late = ?, reason = ?, voice_note_id = ?, closed_at = ?,"
            + " row_version = row_version + 1"
            + " WHERE request_id = ? AND row_version = ? AND state IN ('sent', 'expired')",
        answer, via, by, Timestamp.from(at), late, reason.orElse(null), voiceNoteId.orElse(null), Timestamp.from(at),
        requestId, expectedVersion);
  }

  /** The questions past their deadline with no answer: the expiry job's work. */
  public List<DecisionView> due(Instant now) {
    return database
        .query("SELECT " + COLUMNS + " FROM issues.decision_requests WHERE state = 'sent' AND deadline < ?"
            + " ORDER BY deadline LIMIT 200", Timestamp.from(now))
        .stream()
        .map(JdbcDecisionRequests::view)
        .toList();
  }

  /** Marks one question expired; zero rows when it was answered or withdrawn meanwhile. */
  public int expire(UUID requestId, Instant at) {
    return database.update(
        "UPDATE issues.decision_requests SET state = 'expired', closed_at = ?, row_version = row_version + 1"
            + " WHERE request_id = ? AND state = 'sent'",
        Timestamp.from(at), requestId);
  }

  /** The day's questions the reader may see, newest first: a depot's for the dispatcher, its own for a store. */
  public List<DecisionView> ofDay(LocalDate serviceDate, Optional<String> depotCode) {
    return database
        .query(
            "SELECT " + COLUMNS + " FROM issues.decision_requests WHERE service_date = ?"
                + (depotCode.isPresent() ? " AND depot_code = ?" : "") + " ORDER BY asked_at DESC LIMIT 200",
            depotCode.isPresent()
                ? new Object[] {Date.valueOf(serviceDate), depotCode.get()}
                : new Object[] {Date.valueOf(serviceDate)})
        .stream()
        .map(JdbcDecisionRequests::view)
        .toList();
  }

  private static DecisionView view(Map<String, Object> r) {
    return new DecisionView(
        (UUID) r.get("request_id"),
        (String) r.get("kind"),
        (String) r.get("depot_code"),
        ((Date) r.get("service_date")).toLocalDate(),
        (UUID) r.get("trip_id"),
        (String) r.get("vehicle_id"),
        (UUID) r.get("delivery_id"),
        (String) r.get("outlet_id"),
        Optional.ofNullable((UUID) r.get("about_delivery_id")),
        Optional.ofNullable((String) r.get("about_outlet_id")),
        Optional.ofNullable((Number) r.get("about_units")).map(Number::intValue),
        instant(r.get("receive_at")),
        instant(r.get("arrival_at")),
        ((Timestamp) r.get("deadline")).toInstant(),
        (String) r.get("state"),
        Optional.ofNullable((String) r.get("answer")),
        Optional.ofNullable((String) r.get("answered_via")),
        instant(r.get("answered_at")),
        Boolean.TRUE.equals(r.get("answered_late")),
        Optional.ofNullable((String) r.get("reason")),
        Optional.ofNullable((UUID) r.get("voice_note_id")),
        ((Timestamp) r.get("asked_at")).toInstant(),
        ((Number) r.get("row_version")).longValue(),
        com.waypoint.dispatch.shared.domain.Actor.SYSTEM_ID.equals(r.get("asked_by")));
  }

  private static Optional<Instant> instant(Object value) {
    return Optional.ofNullable((Timestamp) value).map(Timestamp::toInstant);
  }
}
