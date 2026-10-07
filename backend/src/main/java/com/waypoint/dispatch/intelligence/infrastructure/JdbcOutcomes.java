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

  /** One stop's prediction and outcome, for the day's metrics. */
  public record Row(
      String modelLabel, java.util.Optional<Double> lateProb, java.util.Optional<Boolean> late,
      java.util.Optional<Double> predictedService, java.util.Optional<Double> serviceMinutes) {}

  /** One day's metrics for a depot and model (roadmap MF-03). */
  public record DailyMetrics(
      String depotCode, LocalDate day, String modelLabel, int stops, int labelled,
      java.util.Optional<Double> brier, java.util.Optional<Double> logLoss,
      java.util.Optional<Double> calSlope, java.util.Optional<Double> calIntercept,
      java.util.Optional<Double> serviceMae, int serviceN) {}

  private final Database database;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public JdbcOutcomes(Database database, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.database = database;
    this.json = json;
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

  /** A depot's day of stops that had a prediction. */
  public java.util.List<Row> predicted(String depotCode, LocalDate day) {
    return database
        .query(
            "SELECT model_label, predicted_late_prob, late_label, predicted_service, service_minutes"
                + " FROM ml.delivery_outcomes WHERE depot_code = ? AND service_date = ? AND model_label IS NOT NULL",
            depotCode, Date.valueOf(day))
        .stream()
        .map(r -> new Row(
            (String) r.get("model_label"),
            java.util.Optional.ofNullable((Number) r.get("predicted_late_prob")).map(Number::doubleValue),
            java.util.Optional.ofNullable((Boolean) r.get("late_label")),
            java.util.Optional.ofNullable((Number) r.get("predicted_service")).map(Number::doubleValue),
            java.util.Optional.ofNullable((Number) r.get("service_minutes")).map(Number::doubleValue)))
        .toList();
  }

  public void saveMetrics(DailyMetrics m, Instant at) {
    database.update(
        """
        INSERT INTO ml.model_metrics_daily
            (depot_code, service_date, model_label, stops, labelled, brier, log_loss, cal_slope, cal_intercept,
             service_mae, service_n, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (depot_code, service_date, model_label) DO UPDATE SET
            stops = EXCLUDED.stops, labelled = EXCLUDED.labelled, brier = EXCLUDED.brier,
            log_loss = EXCLUDED.log_loss, cal_slope = EXCLUDED.cal_slope, cal_intercept = EXCLUDED.cal_intercept,
            service_mae = EXCLUDED.service_mae, service_n = EXCLUDED.service_n, computed_at = EXCLUDED.computed_at
        """,
        m.depotCode(), Date.valueOf(m.day()), m.modelLabel(), m.stops(), m.labelled(),
        m.brier().orElse(null), m.logLoss().orElse(null), m.calSlope().orElse(null), m.calIntercept().orElse(null),
        m.serviceMae().orElse(null), m.serviceN(), Timestamp.from(at));
  }

  /** R-ML-14: the stops the fallback scored, with a trustworthy label, between two service dates. */
  public java.util.List<com.waypoint.dispatch.intelligence.domain.FallbackTuning.Labelled> fallbackLabelled(
      LocalDate from, LocalDate to) {
    return database
        .query(
            "SELECT outlet_id, predicted_late_prob, late_label FROM ml.delivery_outcomes"
                + " WHERE model_label = 'deterministic' AND late_label IS NOT NULL AND predicted_late_prob IS NOT NULL"
                + " AND service_date BETWEEN ? AND ?",
            Date.valueOf(from), Date.valueOf(to))
        .stream()
        .map(r -> new com.waypoint.dispatch.intelligence.domain.FallbackTuning.Labelled(
            (String) r.get("outlet_id"), ((Number) r.get("predicted_late_prob")).doubleValue(),
            Boolean.TRUE.equals(r.get("late_label"))))
        .toList();
  }

  public void saveTuning(
      java.util.UUID tuningId, com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning t,
      LocalDate from, LocalDate to, Instant at) {
    String offsets;
    try {
      offsets = json.writeValueAsString(new java.util.TreeMap<>(t.outletOffsets()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("outlet offsets could not be written", e);
    }
    database.update(
        "INSERT INTO ml.fallback_tuning (tuning_id, fitted_at, window_from, window_to, labels, intercept, slope,"
            + " outlet_offsets) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        tuningId, Timestamp.from(at), Date.valueOf(from), Date.valueOf(to), t.labels(), t.intercept(), t.slope(),
        offsets);
  }

  /** The newest fit, or the identity when there is none. */
  public com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning currentTuning() {
    return database
        .query(
            "SELECT labels, intercept, slope, outlet_offsets::text AS offsets FROM ml.fallback_tuning"
                + " ORDER BY fitted_at DESC LIMIT 1")
        .stream()
        .findFirst()
        .map(r -> new com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning(
            ((Number) r.get("intercept")).doubleValue(), ((Number) r.get("slope")).doubleValue(),
            offsets((String) r.get("offsets")), ((Number) r.get("labels")).intValue()))
        .orElse(com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning.IDENTITY);
  }

  private Map<String, Double> offsets(String text) {
    try {
      return json.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return Map.of();
    }
  }
}
