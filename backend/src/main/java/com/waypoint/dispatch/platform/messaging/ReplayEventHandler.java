package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Puts a dead-lettered event back in the queue (PLT-03).
 *
 * <p>The attempt counter restarts, so the event gets a full set of tries. Every
 * subscriber that already applied it is skipped by the consumer inbox, so a
 * replay re-runs only the work that failed. A replayed event arrives after the
 * events that followed it, because a dead event never blocked them; the
 * administrator replaying it accepts that.
 *
 * <p>A decision is recorded with an actor, a reason and a time (rule 8): the
 * reason is mandatory and lands in the attempt history as well as the audit log.
 *
 * <p>There is no {@code expected_version} because an outbox row has no business
 * version; the guard is the state itself. Only a {@code dead} row moves, so two
 * administrators replaying the same event cannot both succeed.
 */
@Component
public class ReplayEventHandler implements CommandHandler {
  public static final String ACTION = "platform:ReplayEvent";
  public static final String KIND = "platform.replay-event";
  private static final int REASON_LIMIT = 500;

  private final Database database;
  private final Clock clock;
  private final Metrics metrics;

  public ReplayEventHandler(Database database, Clock clock, Metrics metrics) {
    this.database = database;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String action() {
    return ACTION;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.INTEGRATION;
  }

  @Override
  public String resource(Command command) {
    String eventId = text(command.payload(), "eventId");
    return eventId == null ? null : "wpt:platform:event:" + eventId;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    UUID eventId = eventId(command.payload());
    String reason = text(command.payload(), "reason");
    if (reason == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "reason is required to replay an event");
    }
    if (reason.length() > REASON_LIMIT) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "reason is longer than " + REASON_LIMIT + " characters");
    }

    Map<String, Object> dead =
        database.queryOne(
            "SELECT attempts FROM integration.outbox_events WHERE event_id = ? AND status = 'dead'"
                + " FOR UPDATE",
            eventId);
    if (dead == null) {
      throw new DomainException(
          ErrorCode.CONFLICT, "Event " + eventId + " is not dead-lettered, so it cannot be replayed");
    }

    Timestamp now = Timestamp.from(clock.now());
    database.update(
        "UPDATE integration.outbox_events SET status = 'pending', attempts = 0, next_attempt_at = ?,"
            + " last_error = NULL, dead_lettered_at = NULL, locked_until = NULL"
            + " WHERE event_id = ?",
        now,
        eventId);
    database.update(
        "INSERT INTO integration.outbox_attempts"
            + " (event_id, attempt, attempted_at, outcome, actor_id, note) VALUES (?, ?, ?, 'replayed', ?, ?)",
        eventId,
        ((Number) dead.get("attempts")).intValue(),
        now,
        actor.userId(),
        reason);
    metrics.increment("waypoint.outbox.replayed");
    return Map.of("eventId", eventId.toString(), "status", "pending");
  }

  private static UUID eventId(JsonNode payload) {
    String value = text(payload, "eventId");
    if (value == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "eventId is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "eventId is not a uuid");
    }
  }

  private static String text(JsonNode payload, String field) {
    if (payload == null || !payload.hasNonNull(field)) {
      return null;
    }
    String value = payload.get(field).asText().trim();
    return value.isEmpty() ? null : value;
  }
}
