package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** The suggestions shown beside a stop and the decision taken, append only (docs/issues/decision-suggestions/PLAN.md). */
@Repository
public class JdbcDecisionSuggestions {
  private final Database database;

  public JdbcDecisionSuggestions(Database database) {
    this.database = database;
  }

  /** How often each decision was shown and taken in a situation since {@code since}, in the depots the reader may see. */
  public Tally tally(String situation, Instant since) {
    Map<String, Integer> shown = new HashMap<>();
    Map<String, Integer> chosen = new HashMap<>();
    database
        .query(
            """
            SELECT a AS action, count(*) AS shown, count(*) FILTER (WHERE chosen = a) AS chosen
              FROM ml.decision_suggestions, unnest(shown) AS a
             WHERE situation = ? AND decided_at >= ?
             GROUP BY a
            """,
            situation, Timestamp.from(since))
        .forEach(r -> {
          shown.put((String) r.get("action"), ((Number) r.get("shown")).intValue());
          chosen.put((String) r.get("action"), ((Number) r.get("chosen")).intValue());
        });
    int decided = ((Number) database.queryOne(
            "SELECT count(*) AS n FROM ml.decision_suggestions WHERE situation = ? AND decided_at >= ? AND chosen IS NOT NULL",
            situation, Timestamp.from(since)).get("n")).intValue();
    return new Tally(shown, chosen, decided);
  }

  /** The log as counted, by action key. */
  public record Tally(Map<String, Integer> shown, Map<String, Integer> chosen, int decided) {}

  public void insert(
      UUID suggestionId, UUID deliveryId, String depotCode, String situation, int version, List<String> shown,
      String chosen, UUID decidedBy, Instant decidedAt) {
    database.update(
        """
        INSERT INTO ml.decision_suggestions
            (suggestion_id, delivery_id, depot_code, situation, catalogue_version, shown, chosen, decided_by, decided_at)
        VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?)
        """,
        suggestionId, deliveryId, depotCode, situation, version, "{" + String.join(",", shown) + "}", chosen,
        decidedBy, Timestamp.from(decidedAt));
  }
}
