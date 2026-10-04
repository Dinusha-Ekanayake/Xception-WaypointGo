package com.waypoint.dispatch.warehouse.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code warehouse.inbound_events}, the webhook inbox. The unique
 * {@code (source_system, source_event_id)} makes an exact replay a no-op
 * (SEC-19), and a {@code CHECK} keeps an unverified row out of every processing
 * state (SEC-18).
 */
@Component
public class JdbcInboundEventRepository {
  private final Database database;

  public JdbcInboundEventRepository(Database database) {
    this.database = database;
  }

  public record InboundEvent(
      UUID inboundEventId, String sourceEventId, String eventType, String payload,
      boolean signatureVerified, String status, int attempts) {}

  /** @return true when landed, false when this source event id was already here */
  public boolean land(
      UUID id, String sourceSystem, String sourceEventId, String eventType, String payloadJson,
      boolean verified, String verificationError, String status, Instant at) {
    return database.update(
            "INSERT INTO warehouse.inbound_events (inbound_event_id, source_system, source_event_id,"
                + " event_type, payload, signature_verified, verification_error, received_at, status,"
                + " next_attempt_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (source_system, source_event_id) DO NOTHING",
            id, sourceSystem, sourceEventId, eventType, payloadJson, verified, verificationError,
            Timestamp.from(at), status, Timestamp.from(at))
        == 1;
  }

  public List<InboundEvent> due(Instant now, int limit) {
    return database.query(
            "SELECT inbound_event_id, source_event_id, event_type, payload, signature_verified, status,"
                + " attempts FROM warehouse.inbound_events"
                + " WHERE status IN ('received','failed') AND signature_verified"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
                + " ORDER BY received_at LIMIT ?",
            Timestamp.from(now), limit)
        .stream().map(JdbcInboundEventRepository::map).toList();
  }

  public Optional<InboundEvent> find(UUID id) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT inbound_event_id, source_event_id, event_type, payload, signature_verified, status,"
                + " attempts FROM warehouse.inbound_events WHERE inbound_event_id = ?",
            id);
    return Optional.ofNullable(row).map(JdbcInboundEventRepository::map);
  }

  public void mark(UUID id, String status, String error, Instant at) {
    database.update(
        "UPDATE warehouse.inbound_events SET status = ?, last_error = ?, attempts = attempts + 1,"
            + " processed_at = CASE WHEN ? = 'processed' THEN ? ELSE processed_at END"
            + " WHERE inbound_event_id = ?",
        status, error, status, Timestamp.from(at), id);
  }

  public void retryAt(UUID id, Instant next, String error) {
    database.update(
        "UPDATE warehouse.inbound_events SET status = 'failed', attempts = attempts + 1,"
            + " next_attempt_at = ?, last_error = ? WHERE inbound_event_id = ?",
        Timestamp.from(next), error, id);
  }

  /** An administrator's decision on a quarantined event, with who and why (rule 8). */
  public void decide(UUID id, String status, UUID actor, String reason, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE warehouse.inbound_events SET status = ?, decided_by = ?, decision_reason = ?,"
            + " next_attempt_at = ? WHERE inbound_event_id = ? AND status = 'quarantined'",
        status, actor, reason, Timestamp.from(at), id);
  }

  private static InboundEvent map(Map<String, Object> row) {
    return new InboundEvent(
        (UUID) row.get("inbound_event_id"),
        (String) row.get("source_event_id"),
        (String) row.get("event_type"),
        String.valueOf(row.get("payload")),
        Boolean.TRUE.equals(row.get("signature_verified")),
        (String) row.get("status"),
        ((Number) row.get("attempts")).intValue());
  }
}
