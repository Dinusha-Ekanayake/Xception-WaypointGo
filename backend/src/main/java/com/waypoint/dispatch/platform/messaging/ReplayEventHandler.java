package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
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
 * <p>An event is dead because every subscriber attempt failed; replaying it is
 * what an administrator does after fixing the cause. Its attempts start again,
 * and the last error is kept so the history is not lost. Subscribers that had
 * already applied it are skipped by the inbox, so only the ones that failed run.
 *
 * <p>Events of the same aggregate written after it were delivered while it was
 * dead, so a replayed event arrives out of order. Subscribers are written to
 * tolerate that; the alternative, a dead event blocking its aggregate for ever,
 * is the failure the dead letter exists to prevent.
 */
@Component
public class ReplayEventHandler implements CommandHandler {
  public static final String ACTION = "platform:ReplayEvent";

  private final Database database;
  private final AuditLog audit;
  private final Clock clock;
  private final Metrics metrics;
  private final RelaySignal relay;

  public ReplayEventHandler(
      Database database, AuditLog audit, Clock clock, Metrics metrics, RelaySignal relay) {
    this.database = database;
    this.audit = audit;
    this.clock = clock.realTime();
    this.metrics = metrics;
    this.relay = relay;
  }

  @Override
  public String kind() {
    return ACTION;
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
    UUID eventId = CommandPayload.of(command).optionalUuid("eventId");
    return eventId == null ? null : resourceOf(eventId);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    UUID eventId = CommandPayload.of(command).uuid("eventId");

    // The status in the WHERE is the version guard: a second replay, or one
    // racing the relay, changes no row and is told why.
    int replayed =
        database.update(
            "UPDATE integration.outbox_events"
                + " SET status = 'pending', attempts = 0, next_attempt_at = ?, dead_lettered_at = NULL"
                + " WHERE event_id = ? AND status = 'dead'",
            Timestamp.from(clock.now()),
            eventId);
    if (replayed == 0) {
      Map<String, Object> row =
          database.queryOne(
              "SELECT status FROM integration.outbox_events WHERE event_id = ?", eventId);
      if (row == null) {
        throw new DomainException(ErrorCode.NOT_FOUND, "No such event");
      }
      throw new DomainException(
          ErrorCode.CONFLICT, "Only a dead-lettered event can be replayed; this one is " + row.get("status"));
    }

    audit.record(
        AuditEntry.allowed(
            actor.userId(), actor.deviceId(), ACTION, resourceOf(eventId), "dead letter replayed"));
    metrics.increment("waypoint.outbox.replayed");
    database.afterCommit(relay::signal);
    return Map.of("eventId", eventId.toString(), "status", "pending");
  }

  static String resourceOf(UUID eventId) {
    return "wpt:platform:event:" + eventId;
  }
}
