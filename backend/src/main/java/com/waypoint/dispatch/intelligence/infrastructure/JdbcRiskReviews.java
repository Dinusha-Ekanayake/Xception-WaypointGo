package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.intelligence.contract.RiskReviewViews.ReviewView;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** T1-01 risk reviews, append only. Row-level security keeps them to the actor's depots. */
@Repository
public class JdbcRiskReviews {
  private final Database database;

  public JdbcRiskReviews(Database database) {
    this.database = database;
  }

  public void insert(ReviewView r, String depotCode, int percent) {
    database.update(
        """
        INSERT INTO ml.risk_reviews (review_id, plan_id, depot_code, order_id, percent, choice, reason, decided_by, decided_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        r.reviewId(), r.planId(), depotCode, r.orderId(), percent, r.choice(), r.reason(), r.decidedBy(),
        Timestamp.from(r.decidedAt()));
  }

  /** The latest choice for each stop of a plan. */
  public Map<UUID, ReviewView> latest(UUID planId) {
    Map<UUID, ReviewView> out = new HashMap<>();
    database.query(
            """
            SELECT DISTINCT ON (order_id) review_id, plan_id, order_id, choice, reason, decided_by, decided_at
            FROM ml.risk_reviews WHERE plan_id = ? ORDER BY order_id, decided_at DESC, review_id DESC
            """,
            planId)
        .forEach(r -> out.put((UUID) r.get("order_id"), new ReviewView(
            (UUID) r.get("review_id"), (UUID) r.get("plan_id"), (UUID) r.get("order_id"), (String) r.get("choice"),
            (String) r.get("reason"), (UUID) r.get("decided_by"), ((Timestamp) r.get("decided_at")).toInstant())));
    return out;
  }
}
