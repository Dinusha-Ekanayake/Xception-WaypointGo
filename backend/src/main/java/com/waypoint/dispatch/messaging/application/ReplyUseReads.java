package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.ReplyUseQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link ReplyUseQuery}: the ready replies' record, for the ranker (AI-J5). */
@Component
public class ReplyUseReads implements ReplyUseQuery {
  /** A ranker needs months, not years; this bounds one read. */
  static final int MAX_ROWS = 20_000;

  private final Database database;

  public ReplyUseReads(Database database) {
    this.database = database;
  }

  @Override
  public List<ReplyChoice> choices(Instant from, Instant to) {
    // A separate read as the same person: the caller is in its own module's transaction.
    List<Map<String, Object>> rows =
        database.readAs(
            ModuleRole.MESSAGING,
            database.ambientActor().orElse(null),
            () -> database.query(
                """
                SELECT used_at, template_id, rank, edited, shown, coalesce(explored, false) AS explored
                FROM messaging.reply_uses
                WHERE shown IS NOT NULL AND used_at >= ? AND used_at <= ?
                ORDER BY used_at
                LIMIT ?
                """,
                Timestamp.from(from), Timestamp.from(to), MAX_ROWS));
    return rows.stream()
        .map(
            r -> new ReplyChoice(
                ((Timestamp) r.get("used_at")).toInstant(),
                (String) r.get("template_id"),
                ((Number) r.get("rank")).intValue(),
                (Boolean) r.get("edited"),
                texts(r.get("shown")),
                (Boolean) r.get("explored")))
        .toList();
  }

  private static List<String> texts(Object value) {
    if (value instanceof Array array) {
      try {
        return List.of((String[]) array.getArray());
      } catch (SQLException e) {
        throw new IllegalStateException("Could not read the shown replies", e);
      }
    }
    if (value instanceof String[] values) {
      return List.of(values);
    }
    return List.of();
  }
}
