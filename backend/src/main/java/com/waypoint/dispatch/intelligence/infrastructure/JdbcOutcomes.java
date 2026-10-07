package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.execution.contract.ExecutionViews.StopActualView;
import com.waypoint.dispatch.intelligence.domain.OutcomeLabel;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Stops' outcomes beside their predictions (ml.delivery_outcomes, roadmap MF-02). Written as the system. */
@Repository
public class JdbcOutcomes {
  public record Coverage(long stops, long labelled, long late) {}

  private final Database database;

  public JdbcOutcomes(Database database) {
    this.database = database;
  }

  /**
   * Records or refreshes one stop's outcome, with the newest prediction for its
   * order (the model's over a degraded one at the same time).
   */
  public void record(StopActualView a, OutcomeLabel.Label label, Instant at) {
    database.update(
        """
        INSERT INTO ml.delivery_outcomes
            (delivery_id, order_id, outlet_id, depot_code, service_date, stop_sequence, outcome, arrived_at,
             completed_at, late_minutes, service_minutes, late_label, label_source, prediction_id,
             predicted_late_prob, predicted_service, model_label, recorded_at)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, p.prediction_id, p.late_prob, p.service_min, p.model_label, ?
          FROM (SELECT 1) one
          LEFT JOIN LATERAL (
                SELECT prediction_id, late_prob, service_min, model_label FROM ml.delivery_predictions
                 WHERE order_id = ? ORDER BY generated_at DESC, degraded LIMIT 1) p ON true
        ON CONFLICT (delivery_id) DO UPDATE SET
            outcome = EXCLUDED.outcome, arrived_at = EXCLUDED.arrived_at, completed_at = EXCLUDED.completed_at,
            late_minutes = EXCLUDED.late_minutes, service_minutes = EXCLUDED.service_minutes,
            late_label = EXCLUDED.late_label, label_source = EXCLUDED.label_source,
            prediction_id = EXCLUDED.prediction_id, predicted_late_prob = EXCLUDED.predicted_late_prob,
            predicted_service = EXCLUDED.predicted_service, model_label = EXCLUDED.model_label,
            recorded_at = EXCLUDED.recorded_at
        """,
        a.deliveryId(), a.orderId(), a.outletId(), a.depotCode(), Date.valueOf(a.serviceDate()), a.stopSequence(),
        a.outcome().name().toLowerCase(java.util.Locale.ROOT),
        a.arrivedAt().map(Timestamp::from).orElse(null), a.completedAt().map(Timestamp::from).orElse(null),
        a.lateMinutes().orElse(null), a.serviceMinutes().orElse(null), label.late().orElse(null),
        label.source().code(), Timestamp.from(at), a.orderId());
  }

  /** A depot's day: how many stops, how many with a label, how many of those late. */
  public Coverage coverage(String depotCode, LocalDate day) {
    Map<String, Object> r =
        database.queryOne(
            "SELECT count(*) AS stops, count(late_label) AS labelled, count(*) FILTER (WHERE late_label) AS late"
                + " FROM ml.delivery_outcomes WHERE depot_code = ? AND service_date = ?",
            depotCode, Date.valueOf(day));
    return new Coverage(
        ((Number) r.get("stops")).longValue(), ((Number) r.get("labelled")).longValue(),
        ((Number) r.get("late")).longValue());
  }
}
