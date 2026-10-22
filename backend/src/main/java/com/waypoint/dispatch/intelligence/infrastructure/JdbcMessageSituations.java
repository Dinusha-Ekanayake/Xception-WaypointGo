package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** {@code ml.message_situations}: what Jev read from a driver's or store's own words (R-ML-35). */
@Repository
public class JdbcMessageSituations {
  private final Database database;

  public JdbcMessageSituations(Database database) {
    this.database = database;
  }

  public record Pending(UUID messageId, String maskedText, List<String> optionOrder, int attempts) {}

  /** A message read for the trip's card. */
  public record Read(UUID messageId, String situation, double confidence, String authorRole, Optional<String> outletId,
      String source, Instant at, boolean unclear, Optional<String> confirmed) {}

  /** Queued once; a retried event changes nothing. */
  public void queue(UUID messageId, String tripId, String depotCode, String authorRole, Optional<String> outletId,
      String source, String maskedText, List<String> optionOrder, Instant at) {
    queue(messageId, tripId, depotCode, authorRole, outletId, source, maskedText, optionOrder, at, false);
  }

  /** @param unclear a voice note the engine heard unclearly (R-ML-37) */
  public void queue(UUID messageId, String tripId, String depotCode, String authorRole, Optional<String> outletId,
      String source, String maskedText, List<String> optionOrder, Instant at, boolean unclear) {
    database.update(
        "INSERT INTO ml.message_situations (message_id, trip_id, depot_code, author_role, outlet_id, source, masked_text,"
            + " option_order, created_at, unclear) VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?) ON CONFLICT (message_id) DO NOTHING",
        messageId, tripId, depotCode, authorRole, outletId.orElse(null), source, maskedText,
        "{" + String.join(",", optionOrder) + "}", Timestamp.from(at), unclear);
  }

  public List<Pending> pending(int limit) {
    return database.query(
            "SELECT message_id, masked_text, option_order, attempts FROM ml.message_situations"
                + " WHERE status = 'pending' ORDER BY created_at LIMIT ?",
            limit)
        .stream()
        .map(r -> new Pending((UUID) r.get("message_id"), (String) r.get("masked_text"),
            JdbcJevShadow.strings(r.get("option_order")), ((Number) r.get("attempts")).intValue()))
        .toList();
  }

  public long answeredSince(Instant since) {
    return ((Number) database.queryOne(
            "SELECT count(*) AS n FROM ml.message_situations WHERE answered_at >= ?", Timestamp.from(since))
        .get("n")).longValue();
  }

  public void answered(UUID messageId, String situation, double confidence, String model, String promptVersion, Instant at) {
    database.update(
        "UPDATE ml.message_situations SET status = 'answered', attempts = attempts + 1, situation = ?, confidence = ?,"
            + " model = ?, prompt_version = ?, answered_at = ? WHERE message_id = ? AND status = 'pending'",
        situation, confidence, model, promptVersion, Timestamp.from(at), messageId);
  }

  public void missed(UUID messageId, boolean giveUp) {
    database.update(
        "UPDATE ml.message_situations SET attempts = attempts + 1, status = CASE WHEN ? THEN 'failed' ELSE status END"
            + " WHERE message_id = ? AND status = 'pending'",
        giveUp, messageId);
  }

  /** A trip's messages Jev has read and nobody dismissed, oldest first; row-level security keeps it to the reader's depots. */
  public List<Read> forTrip(String tripId) {
    return database.query(
            "SELECT message_id, situation, confidence, author_role, outlet_id, source, created_at, unclear, reviewed_situation"
                + " FROM ml.message_situations WHERE trip_id = ? AND status = 'answered' AND dismissed_at IS NULL"
                + " ORDER BY created_at, message_id",
            tripId)
        .stream()
        .map(r -> new Read((UUID) r.get("message_id"), (String) r.get("situation"),
            ((Number) r.get("confidence")).doubleValue(), (String) r.get("author_role"),
            Optional.ofNullable((String) r.get("outlet_id")), (String) r.get("source"),
            ((Timestamp) r.get("created_at")).toInstant(), Boolean.TRUE.equals(r.get("unclear")),
            Optional.ofNullable((String) r.get("reviewed_situation"))))
        .toList();
  }

  /** Rule 9: a trip's words that could not be read, waiting since before {@code waitingSince} or given up on. */
  public int unread(String tripId, Instant waitingSince) {
    return ((Number) database.queryOne(
        "SELECT count(*) AS n FROM ml.message_situations WHERE trip_id = ?"
            + " AND (status = 'failed' OR (status = 'pending' AND created_at < ?))",
        tripId, Timestamp.from(waitingSince)).get("n")).intValue();
  }

  /** Rule 8: who took it off the card, why and when. False when it is not on this trip or was already off it. */
  public boolean dismiss(UUID messageId, String tripId, UUID by, String reason, Instant at) {
    return database.update(
        "UPDATE ml.message_situations SET dismissed_at = ?, dismissed_by = ?, dismiss_reason = ?"
            + " WHERE message_id = ? AND trip_id = ? AND dismissed_at IS NULL",
        Timestamp.from(at), by, reason, messageId, tripId) == 1;
  }

  /** Rule 8: what the dispatcher confirmed a message reports, by whom and when; the latest confirmation stands. */
  public boolean confirm(UUID messageId, String tripId, String situation, UUID by, Instant at) {
    return database.update(
        "UPDATE ml.message_situations SET reviewed_situation = ?, reviewed_by = ?, reviewed_at = ?"
            + " WHERE message_id = ? AND trip_id = ? AND status = 'answered'",
        situation, by, Timestamp.from(at), messageId, tripId) == 1;
  }

  public Optional<Map<String, Object>> row(UUID messageId) {
    return Optional.ofNullable(database.queryOne(
        "SELECT trip_id, depot_code, dismissed_at FROM ml.message_situations WHERE message_id = ?", messageId));
  }
}
