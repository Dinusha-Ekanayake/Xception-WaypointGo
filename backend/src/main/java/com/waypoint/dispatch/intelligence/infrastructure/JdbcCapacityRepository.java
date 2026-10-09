package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityAction;
import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityDecisionView;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Capacity decisions (roadmap T2-01). Insert and read only: a decision is never changed. */
@Repository
public class JdbcCapacityRepository {
  private final Database database;

  public JdbcCapacityRepository(Database database) {
    this.database = database;
  }

  public void insert(CapacityDecisionView d) {
    database.update(
        """
        INSERT INTO ml.capacity_decisions
            (decision_id, depot_code, iso_year, iso_week, action, vehicles, refrigerated, reason, decided_by, decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        d.decisionId(), d.depotCode(), d.isoYear(), d.isoWeek(), d.action().name(), d.vehicles().orElse(null),
        d.refrigerated(), d.reason(), d.decidedBy(), Timestamp.from(d.decidedAt()));
  }

  /** A depot's decisions from a week on, newest first. Row-level security keeps it to the actor's depots. */
  public List<CapacityDecisionView> fromWeek(String depotCode, int fromYear, int fromWeek) {
    return database.query(
            """
            SELECT decision_id, depot_code, iso_year, iso_week, action, vehicles, refrigerated, reason, decided_by, decided_at
              FROM ml.capacity_decisions
             WHERE depot_code = ? AND (iso_year, iso_week) >= (?, ?)
             ORDER BY decided_at DESC, decision_id DESC
             LIMIT 200
            """,
            depotCode, fromYear, fromWeek)
        .stream()
        .map(JdbcCapacityRepository::view)
        .toList();
  }

  private static CapacityDecisionView view(Map<String, Object> r) {
    return new CapacityDecisionView(
        (UUID) r.get("decision_id"),
        (String) r.get("depot_code"),
        ((Number) r.get("iso_year")).intValue(),
        ((Number) r.get("iso_week")).intValue(),
        CapacityAction.valueOf((String) r.get("action")),
        Optional.ofNullable((Number) r.get("vehicles")).map(Number::intValue),
        Boolean.TRUE.equals(r.get("refrigerated")),
        (String) r.get("reason"),
        (UUID) r.get("decided_by"),
        ((Timestamp) r.get("decided_at")).toInstant());
  }

}
