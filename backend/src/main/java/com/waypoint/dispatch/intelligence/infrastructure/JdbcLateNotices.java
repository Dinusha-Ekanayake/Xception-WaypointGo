package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Likely-late notices given, and the precision behind the store gate (roadmap T1-02). */
@Component
public class JdbcLateNotices {
  private final Database database;

  public JdbcLateNotices(Database database) {
    this.database = database;
  }

  public boolean told(UUID orderId) {
    return !database.query("SELECT 1 FROM ml.late_notices WHERE order_id = ?", orderId).isEmpty();
  }

  /** Notices the store itself was given for deliveries in these days. */
  public int storeNotices(String outletId, LocalDate from, LocalDate to) {
    return ((Number) database.queryOne(
            "SELECT count(*) AS n FROM ml.late_notices WHERE outlet_id = ? AND audience = 'store'"
                + " AND service_date BETWEEN ? AND ?",
            outletId, Date.valueOf(from), Date.valueOf(to))
        .get("n")).intValue();
  }

  /** @return false when another run told it first; nothing is then published */
  public boolean record(UUID orderId, UUID planId, String outletId, String depot, LocalDate day, BigDecimal lateProb,
      String modelLabel, String audience, Instant at) {
    return database.update(
            """
            INSERT INTO ml.late_notices
                (order_id, plan_id, outlet_id, depot_code, service_date, late_prob, model_label, audience, noticed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
            """,
            orderId, planId, outletId, depot, Date.valueOf(day), lateProb, modelLabel, audience, Timestamp.from(at))
        == 1;
  }

  /** Of the model's labelled stops flagged at or above {@code threshold} in these days: {late, flagged}. */
  public int[] flagPrecision(String modelLabel, double threshold, LocalDate from, LocalDate to) {
    var r = database.queryOne(
        "SELECT count(*) FILTER (WHERE late_label) AS late, count(*) AS flagged FROM ml.delivery_outcomes"
            + " WHERE model_label = ? AND service_date BETWEEN ? AND ? AND late_label IS NOT NULL"
            + " AND predicted_late_prob >= ?",
        modelLabel, Date.valueOf(from), Date.valueOf(to), BigDecimal.valueOf(threshold));
    return new int[] {((Number) r.get("late")).intValue(), ((Number) r.get("flagged")).intValue()};
  }

  /** The threshold frozen for this model's late flag (MF-04), if one has been. */
  public java.util.Optional<Double> frozenThreshold(String modelLabel) {
    return database.query("SELECT threshold FROM ml.late_thresholds WHERE model_label = ?", modelLabel).stream()
        .findFirst().map(r -> ((Number) r.get("threshold")).doubleValue());
  }

  /** The models that predicted labelled stops in these days and have no frozen threshold yet. */
  public java.util.List<String> unfrozenLabels(LocalDate from, LocalDate to) {
    return database.query(
            "SELECT DISTINCT o.model_label FROM ml.delivery_outcomes o"
                + " WHERE o.service_date BETWEEN ? AND ? AND o.late_label IS NOT NULL AND o.model_label IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM ml.late_thresholds t WHERE t.model_label = o.model_label)"
                + " ORDER BY 1",
            Date.valueOf(from), Date.valueOf(to))
        .stream().map(r -> (String) r.get("model_label")).toList();
  }

  /** What the model predicted for its labelled stops in these days, and what happened. */
  public java.util.List<com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored> scored(
      String modelLabel, LocalDate from, LocalDate to) {
    return database.query(
            "SELECT predicted_late_prob, late_label FROM ml.delivery_outcomes WHERE model_label = ?"
                + " AND service_date BETWEEN ? AND ? AND late_label IS NOT NULL AND predicted_late_prob IS NOT NULL",
            modelLabel, Date.valueOf(from), Date.valueOf(to))
        .stream()
        .map(r -> new com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored(
            ((Number) r.get("predicted_late_prob")).doubleValue(), (Boolean) r.get("late_label")))
        .toList();
  }

  /** Frozen once: a second freeze of the same model changes nothing. */
  public void freeze(String modelLabel, com.waypoint.dispatch.intelligence.domain.LateThreshold.Choice c,
      LocalDate from, LocalDate to, Instant at) {
    database.update(
        """
        INSERT INTO ml.late_thresholds
            (model_label, threshold, flagged, late, precision_lower, labelled, window_from, window_to, frozen_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (model_label) DO NOTHING
        """,
        modelLabel, BigDecimal.valueOf(c.threshold()), c.flagged(), c.late(),
        BigDecimal.valueOf(c.precisionLower()).setScale(4, java.math.RoundingMode.HALF_UP), c.labelled(),
        Date.valueOf(from), Date.valueOf(to), Timestamp.from(at));
  }
}
