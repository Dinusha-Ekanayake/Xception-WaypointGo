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
            "SELECT count(*) AS n FROM ml.decision_suggestions WHERE situation = ? AND decided_at >= ?"
                + " AND (chosen IS NOT NULL OR taken IS NOT NULL)",
            situation, Timestamp.from(since)).get("n")).intValue();
    return new Tally(shown, chosen, decided);
  }

  /** The log as counted, by action key. */
  public record Tally(Map<String, Integer> shown, Map<String, Integer> chosen, int decided) {}

  /** The chips that were on screen beside a report, before anything was decided. */
  public void impression(
      UUID impressionId, UUID deliveryId, String depotCode, String situation, int version, List<String> shown,
      UUID shownBy, Instant shownAt) {
    database.update(
        """
        INSERT INTO ml.suggestion_impressions
            (impression_id, delivery_id, depot_code, situation, catalogue_version, shown, shown_by, shown_at)
        VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?)
        """,
        impressionId, deliveryId, depotCode, situation, version, "{" + String.join(",", shown) + "}", shownBy,
        Timestamp.from(shownAt));
  }

  /** The newest impression of a stop since a time, if no decision was logged for the stop after it. */
  public java.util.Optional<Map<String, Object>> undecidedImpression(UUID deliveryId, Instant since) {
    return java.util.Optional.ofNullable(database.queryOne(
        """
        SELECT i.depot_code, i.situation, i.catalogue_version, array_to_string(i.shown, ',') AS shown, i.shown_at
          FROM ml.suggestion_impressions i
         WHERE i.delivery_id = ? AND i.shown_at >= ?
           AND NOT EXISTS (SELECT 1 FROM ml.decision_suggestions d
                            WHERE d.delivery_id = i.delivery_id AND d.decided_at >= i.shown_at)
         ORDER BY i.shown_at DESC LIMIT 1
        """,
        deliveryId, Timestamp.from(since)));
  }

  /** A decision taken without a chip, from the impression before it. */
  public void insertTaken(
      UUID suggestionId, UUID deliveryId, String depotCode, String situation, int version, List<String> shown,
      String chosen, String taken, UUID decidedBy, Instant decidedAt) {
    database.update(
        """
        INSERT INTO ml.decision_suggestions
            (suggestion_id, delivery_id, depot_code, situation, catalogue_version, shown, chosen, taken, via_chip,
             decided_by, decided_at)
        VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, false, ?, ?)
        """,
        suggestionId, deliveryId, depotCode, situation, version, "{" + String.join(",", shown) + "}", chosen, taken,
        decidedBy, Timestamp.from(decidedAt));
  }

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
