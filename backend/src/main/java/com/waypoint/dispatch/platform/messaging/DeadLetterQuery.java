package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Events the relay gave up on, for the administrator who decides whether to
 * replay them (PLT-03). Never the payload: it can carry personal data, and the
 * cause of a failure is in {@code lastError} and the attempt history.
 *
 * <p>The caller has already been authorized for {@link #READ}. Reads are keyset
 * paginated on {@code (dead_lettered_at, event_id)}, newest first.
 */
@Component
public class DeadLetterQuery {
  /** Reading dead letters needs the same grant as replaying them. */
  public static final String READ = ReplayEventHandler.ACTION;

  private final Database database;

  public DeadLetterQuery(Database database) {
    this.database = database;
  }

  public record AttemptView(
      int attempt, Instant attemptedAt, String outcome, String error, UUID actorId, String note) {}

  public record DeadLetterView(
      UUID eventId,
      String eventType,
      String producer,
      String aggregateType,
      String aggregateId,
      Instant occurredAt,
      Instant deadLetteredAt,
      int attempts,
      String lastError,
      List<AttemptView> history) {}

  public Page<DeadLetterView> list(Actor actor, Optional<String> cursor, int limit) {
    int size = Page.limit(limit);
    List<String> after = Cursor.decode(cursor.orElse(null), 2);
    Timestamp afterAt = null;
    UUID afterId = null;
    if (!after.isEmpty()) {
      try {
        afterAt = Timestamp.from(Instant.parse(after.get(0)));
        afterId = UUID.fromString(after.get(1));
      } catch (DateTimeParseException | IllegalArgumentException e) {
        throw Cursor.invalid();
      }
    }
    Timestamp keyAt = afterAt;
    UUID keyId = afterId;
    List<Map<String, Object>> rows =
        database.asModule(
            ModuleRole.INTEGRATION,
            actor.userId(),
            () ->
                database.query(
                    """
                    SELECT event_id, event_type, producer, aggregate_type, aggregate_id,
                           occurred_at, dead_lettered_at, attempts, last_error
                      FROM integration.outbox_events
                     WHERE status = 'dead'
                       AND (?::timestamptz IS NULL
                            OR (dead_lettered_at, event_id) < (?::timestamptz, ?::uuid))
                     ORDER BY dead_lettered_at DESC, event_id DESC
                     LIMIT ?
                    """,
                    keyAt,
                    keyAt,
                    keyId,
                    size + 1));
    List<DeadLetterView> views = rows.stream().map(row -> view(row, List.of())).toList();
    return Page.fromOverfetch(
        views,
        size,
        v -> Cursor.encode(v.deadLetteredAt().toString(), v.eventId().toString()));
  }

  public DeadLetterView get(Actor actor, UUID eventId) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        actor.userId(),
        () -> {
          Map<String, Object> row =
              database.queryOne(
                  """
                  SELECT event_id, event_type, producer, aggregate_type, aggregate_id,
                         occurred_at, dead_lettered_at, attempts, last_error
                    FROM integration.outbox_events
                   WHERE event_id = ? AND status = 'dead'
                  """,
                  eventId);
          if (row == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "No dead-lettered event " + eventId);
          }
          List<AttemptView> history = new ArrayList<>();
          for (Map<String, Object> attempt :
              database.query(
                  "SELECT attempt, attempted_at, outcome, error, actor_id, note"
                      + " FROM integration.outbox_attempts WHERE event_id = ?"
                      + " ORDER BY attempted_at, attempt_id",
                  eventId)) {
            history.add(
                new AttemptView(
                    ((Number) attempt.get("attempt")).intValue(),
                    ((Timestamp) attempt.get("attempted_at")).toInstant(),
                    (String) attempt.get("outcome"),
                    (String) attempt.get("error"),
                    (UUID) attempt.get("actor_id"),
                    (String) attempt.get("note")));
          }
          return view(row, history);
        });
  }

  private static DeadLetterView view(Map<String, Object> row, List<AttemptView> history) {
    return new DeadLetterView(
        (UUID) row.get("event_id"),
        (String) row.get("event_type"),
        (String) row.get("producer"),
        (String) row.get("aggregate_type"),
        (String) row.get("aggregate_id"),
        ((Timestamp) row.get("occurred_at")).toInstant(),
        ((Timestamp) row.get("dead_lettered_at")).toInstant(),
        ((Number) row.get("attempts")).intValue(),
        (String) row.get("last_error"),
        history);
  }
}
