package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** The suggestions shown beside a stop and the decision taken, append only (docs/issues/decision-suggestions/PLAN.md). */
@Repository
public class JdbcDecisionSuggestions {
  private final Database database;

  public JdbcDecisionSuggestions(Database database) {
    this.database = database;
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
