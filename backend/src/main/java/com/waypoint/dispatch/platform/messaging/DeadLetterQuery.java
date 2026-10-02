package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The dead letters (PLT-03): events whose delivery was given up on.
 *
 * <p>Deliberately without the payload. It may hold personal data, and what an
 * administrator needs in order to decide on a replay is which event, about
 * what, and why it failed.
 */
@Component
public class DeadLetterQuery {
  private final Database database;

  public DeadLetterQuery(Database database) {
    this.database = database;
  }

  /**
   * @param sequence the write order, and the keyset the list is paged on
   * @param lastError the first subscriber failure of the final attempt
   */
  public record DeadLetterView(
      UUID eventId,
      long sequence,
      String type,
      String aggregateType,
      String aggregateId,
      String producer,
      int attempts,
      String lastError,
      Instant occurredAt,
      Instant deadLetteredAt) {}

  /** Newest first. */
  public Page<DeadLetterView> deadLetters(Actor actor, String cursor, Integer limit) {
    int size = Page.limit(limit);
    List<String> after = Cursor.decode(cursor, 1);
    long before;
    try {
      before = after.isEmpty() ? Long.MAX_VALUE : Long.parseLong(after.get(0));
    } catch (NumberFormatException e) {
      throw Cursor.invalid();
    }
    List<DeadLetterView> rows =
        database.readAs(
            ModuleRole.INTEGRATION,
            actor.userId(),
            () ->
                database
                    .query(
                        """
                        SELECT event_id, seq, event_type, aggregate_type, aggregate_id, producer,
                               attempts, last_error, occurred_at, dead_lettered_at
                          FROM integration.outbox_events
                         WHERE status = 'dead' AND seq < ?
                         ORDER BY seq DESC
                         LIMIT ?
                        """,
                        before,
                        size + 1)
                    .stream()
                    .map(DeadLetterQuery::view)
                    .toList());
    return Page.fromOverfetch(rows, size, row -> Cursor.encode(String.valueOf(row.sequence())));
  }

  private static DeadLetterView view(Map<String, Object> row) {
    Timestamp dead = (Timestamp) row.get("dead_lettered_at");
    return new DeadLetterView(
        (UUID) row.get("event_id"),
        ((Number) row.get("seq")).longValue(),
        (String) row.get("event_type"),
        (String) row.get("aggregate_type"),
        (String) row.get("aggregate_id"),
        (String) row.get("producer"),
        ((Number) row.get("attempts")).intValue(),
        (String) row.get("last_error"),
        ((Timestamp) row.get("occurred_at")).toInstant(),
        dead == null ? null : dead.toInstant());
  }
}
