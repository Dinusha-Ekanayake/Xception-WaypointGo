package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Forecast accuracy and prediction drift (roadmap MF-03, R-ML-13). Read and written as the system. */
@Repository
public class JdbcModelQuality {
  /** The forecast for a week as it stood before the week began. */
  public record WeekForecast(String modelLabel, Instant generatedAt, BigDecimal totalM3, BigDecimal chilledM3) {}

  private final Database database;

  public JdbcModelQuality(Database database) {
    this.database = database;
  }

  /** The newest forecast for the week made before {@code before}, the start of the week. */
  public Optional<WeekForecast> forecastBefore(String depot, String brand, int isoYear, int isoWeek, Instant before) {
    return database
        .query(
            """
            SELECT model_label, generated_at, total_m3, chilled_m3 FROM ml.demand_forecasts
             WHERE depot_code = ? AND brand_code = ? AND iso_year = ? AND iso_week = ? AND generated_at < ?
             ORDER BY generated_at DESC, degraded LIMIT 1
            """,
            depot, brand, isoYear, isoWeek, Timestamp.from(before))
        .stream()
        .findFirst()
        .map(r -> new WeekForecast(
            (String) r.get("model_label"), instant(r.get("generated_at")),
            (BigDecimal) r.get("total_m3"), (BigDecimal) r.get("chilled_m3")));
  }

  public void saveAccuracy(
      String depot, String brand, int isoYear, int isoWeek, WeekForecast f, BigDecimal actualTotal,
      BigDecimal actualChilled, Instant at) {
    database.update(
        """
        INSERT INTO ml.forecast_accuracy
            (depot_code, brand_code, iso_year, iso_week, model_label, forecast_generated_at, forecast_total_m3,
             actual_total_m3, forecast_chilled_m3, actual_chilled_m3, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (depot_code, brand_code, iso_year, iso_week) DO UPDATE SET
            model_label = EXCLUDED.model_label, forecast_generated_at = EXCLUDED.forecast_generated_at,
            forecast_total_m3 = EXCLUDED.forecast_total_m3, actual_total_m3 = EXCLUDED.actual_total_m3,
            forecast_chilled_m3 = EXCLUDED.forecast_chilled_m3, actual_chilled_m3 = EXCLUDED.actual_chilled_m3,
            computed_at = EXCLUDED.computed_at
        """,
        depot, brand, isoYear, (short) isoWeek, f.modelLabel(), Timestamp.from(f.generatedAt()), f.totalM3(),
        actualTotal, f.chilledM3(), actualChilled, Timestamp.from(at));
  }

  /** Predicted late probabilities of a depot and model generated in [from, to). */
  public List<Double> lateProbabilities(String depot, String model, Instant from, Instant to) {
    return database
        .query(
            "SELECT late_prob FROM ml.delivery_predictions"
                + " WHERE depot_code = ? AND model_label = ? AND generated_at >= ? AND generated_at < ?",
            depot, model, Timestamp.from(from), Timestamp.from(to))
        .stream()
        .map(r -> ((Number) r.get("late_prob")).doubleValue())
        .toList();
  }

  /** The models that scored a depot's stops in [from, to). */
  public List<String> modelsScoring(String depot, Instant from, Instant to) {
    return database
        .query(
            "SELECT DISTINCT model_label FROM ml.delivery_predictions"
                + " WHERE depot_code = ? AND generated_at >= ? AND generated_at < ? ORDER BY 1",
            depot, Timestamp.from(from), Timestamp.from(to))
        .stream()
        .map(r -> (String) r.get("model_label"))
        .toList();
  }

  public void saveDrift(String depot, LocalDate day, String model, double psi, int referenceN, int currentN, Instant at) {
    database.update(
        """
        INSERT INTO ml.prediction_drift_daily (depot_code, day, model_label, psi, reference_n, current_n, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (depot_code, day, model_label) DO UPDATE SET
            psi = EXCLUDED.psi, reference_n = EXCLUDED.reference_n, current_n = EXCLUDED.current_n,
            computed_at = EXCLUDED.computed_at
        """,
        depot, Date.valueOf(day), model, psi, referenceN, currentN, Timestamp.from(at));
  }

  private static Instant instant(Object value) {
    return value instanceof Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) value).toInstant();
  }

  /** A depot's judged weeks between two week keys (year * 100 + week), oldest first, as the reader may see. */
  public List<java.util.Map<String, Object>> accuracy(String depot, int fromKey, int toKey) {
    return database.query(
        """
        SELECT iso_year, iso_week, brand_code, model_label, forecast_total_m3, actual_total_m3
          FROM ml.forecast_accuracy
         WHERE depot_code = ? AND iso_year * 100 + iso_week BETWEEN ? AND ?
         ORDER BY iso_year, iso_week, brand_code
        """,
        depot, fromKey, toKey);
  }
}
