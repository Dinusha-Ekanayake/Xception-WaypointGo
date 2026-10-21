package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Jev's shadow guesses (ml.jev_shadow). Read and written as the system. */
@Repository
public class JdbcJevShadow {
  public record Pending(UUID issueId, String maskedText, String actualType, List<String> optionOrder, int attempts) {}

  private final Database database;

  public JdbcJevShadow(Database database) {
    this.database = database;
  }

  /** Once per issue: a redelivered event asks nothing new. */
  public void queue(UUID issueId, String depotCode, String maskedText, String actualType, List<String> order, Instant at) {
    database.update(
        "INSERT INTO ml.jev_shadow (issue_id, depot_code, masked_text, actual_type, option_order, created_at)"
            + " VALUES (?, ?, ?, ?, ?::text[], ?) ON CONFLICT (issue_id) DO NOTHING",
        issueId, depotCode, maskedText, actualType, "{" + String.join(",", order) + "}", Timestamp.from(at));
  }

  public List<Pending> pending(int limit) {
    return database.query(
            "SELECT issue_id, masked_text, actual_type, option_order, attempts FROM ml.jev_shadow"
                + " WHERE status = 'pending' ORDER BY created_at LIMIT ?", limit)
        .stream()
        .map(r -> new Pending((UUID) r.get("issue_id"), (String) r.get("masked_text"), (String) r.get("actual_type"),
            strings(r.get("option_order")), ((Number) r.get("attempts")).intValue()))
        .toList();
  }

  public long answeredSince(Instant since) {
    return ((Number) database.queryOne(
        "SELECT count(*) AS n FROM ml.jev_shadow WHERE answered_at >= ?", Timestamp.from(since)).get("n")).longValue();
  }

  public void answered(UUID issueId, String choice, double confidence, String probabilitiesJson, String model,
      boolean agrees, Instant at, String promptVersion) {
    database.update(
        "UPDATE ml.jev_shadow SET status = 'answered', attempts = attempts + 1, jev_choice = ?, confidence = ?,"
            + " probabilities = ?::jsonb, model = ?, agrees = ?, answered_at = ?, prompt_version = ?"
            + " WHERE issue_id = ? AND status = 'pending'",
        choice, BigDecimal.valueOf(confidence).setScale(4, java.math.RoundingMode.HALF_UP), probabilitiesJson, model,
        agrees, Timestamp.from(at), promptVersion, issueId);
  }

  /** No answer: tried again next run, given up after the last attempt. */
  public void missed(UUID issueId, boolean giveUp) {
    database.update(
        "UPDATE ml.jev_shadow SET attempts = attempts + 1, status = CASE WHEN ? THEN 'failed' ELSE status END"
            + " WHERE issue_id = ? AND status = 'pending'",
        giveUp, issueId);
  }

  /** How often Jev agreed with the real type, of the answered: the number the gate reads. */
  public Map<String, Object> agreement() {
    return database.queryOne(
        "SELECT count(*) FILTER (WHERE status = 'answered') AS answered, count(*) FILTER (WHERE agrees) AS agreed"
            + " FROM ml.jev_shadow");
  }

  private static List<String> strings(Object value) {
    if (value instanceof Array array) {
      try {
        return Arrays.asList((String[]) array.getArray());
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
    return List.of();
  }
}
