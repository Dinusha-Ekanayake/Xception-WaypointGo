package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelVersionView;
import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Intelligence in PostgreSQL. Always inside a transaction someone else opened,
 * as {@code waypoint_ml}, so row-level security has already narrowed reads to
 * the actor's depots (or everything, for the process).
 */
@Repository
public class JdbcIntelligenceRepository {
  private static final TypeReference<Map<String, BigDecimal>> METRICS = new TypeReference<>() {};

  private final Database database;
  private final ObjectMapper json;

  public JdbcIntelligenceRepository(Database database, ObjectMapper json) {
    this.database = database;
    this.json = json;
  }

  // ---- model registry ---------------------------------------------------------

  public record ModelRow(UUID id, ModelVersionView view) {}

  private static final String MODEL =
      "SELECT model_version_id, model_name, model_version, kind, status, metrics::text AS metrics, trained_from,"
          + " trained_to, registered_at, activated_at, retired_reason, row_version FROM ml.model_versions";

  public void insertModel(
      UUID id, String name, String version, String kind, Map<String, BigDecimal> metrics, LocalDate from,
      LocalDate to, UUID by, Instant at) {
    database.update(
        """
        INSERT INTO ml.model_versions
            (model_version_id, model_name, model_version, kind, status, metrics, trained_from, trained_to,
             registered_by, registered_at, updated_at)
        VALUES (?, ?, ?, ?, 'registered', ?::jsonb, ?, ?, ?, ?, ?)
        """,
        id, name, version, kind, write(metrics), date(from), date(to), by, Timestamp.from(at), Timestamp.from(at));
  }

  public Optional<ModelRow> model(String name, String version) {
    return models(MODEL + " WHERE model_name = ? AND model_version = ?", name, version).stream().findFirst();
  }

  public Optional<ModelRow> activeModel(String kind) {
    return models(MODEL + " WHERE kind = ? AND status = 'active'", kind).stream().findFirst();
  }

  public List<ModelRow> allModels() {
    return models(MODEL + " ORDER BY kind, registered_at DESC, model_version_id");
  }

  /** The kind's current active model goes back to registered: one active per kind. */
  public int demoteActive(String kind, Instant at) {
    return database.update(
        "UPDATE ml.model_versions SET status = 'registered', row_version = row_version + 1, updated_at = ?"
            + " WHERE kind = ? AND status = 'active'",
        Timestamp.from(at), kind);
  }

  public void activate(UUID id, long expectedVersion, UUID by, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE ml.model_versions SET status = 'active', activated_by = ?, activated_at = ?,"
            + " row_version = row_version + 1, updated_at = ? WHERE model_version_id = ? AND row_version = ?",
        by, Timestamp.from(at), Timestamp.from(at), id, expectedVersion);
  }

  public void retire(UUID id, long expectedVersion, UUID by, String reason, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE ml.model_versions SET status = 'retired', retired_by = ?, retired_at = ?, retired_reason = ?,"
            + " row_version = row_version + 1, updated_at = ? WHERE model_version_id = ? AND row_version = ?",
        by, Timestamp.from(at), reason, Timestamp.from(at), id, expectedVersion);
  }

  private List<ModelRow> models(String sql, Object... params) {
    return database.query(sql, params).stream()
        .map(r -> new ModelRow(
            (UUID) r.get("model_version_id"),
            new ModelVersionView(
                (String) r.get("model_name"),
                (String) r.get("model_version"),
                (String) r.get("kind"),
                ModelStatus.valueOf(((String) r.get("status")).toUpperCase(Locale.ROOT)),
                read((String) r.get("metrics")),
                optionalDate(r.get("trained_from")),
                optionalDate(r.get("trained_to")),
                instant(r.get("registered_at")),
                Optional.ofNullable(r.get("activated_at")).map(JdbcIntelligenceRepository::instant),
                Optional.ofNullable((String) r.get("retired_reason")),
                ((Number) r.get("row_version")).longValue())))
        .toList();
  }

  // ---- plan scoring --------------------------------------------------------------

  /** @return false when the plan was already asked for */
  public boolean requestScoring(UUID planId, String depot, LocalDate date, int planVersion, Instant at) {
    return database.update(
            """
            INSERT INTO ml.plan_scorings (plan_id, depot_code, service_date, plan_version, status, next_attempt_at,
                                          requested_at)
            VALUES (?, ?, ?, ?, 'pending', ?, ?)
            ON CONFLICT (plan_id) DO NOTHING
            """,
            planId, depot, Date.valueOf(date), planVersion, Timestamp.from(at), Timestamp.from(at))
        == 1;
  }

  public record DueScoring(UUID planId, String depotCode, LocalDate serviceDate, int attempts, long rowVersion) {}

  /**
   * Plans due for scoring: pending ones, and degraded ones still within their
   * attempts. Locked so two instances never take the same plan, then leased past
   * the call so it is not claimed again while it is being scored.
   */
  public List<DueScoring> claimDue(Instant now, Instant leaseUntil, int limit, int maxAttempts) {
    List<DueScoring> due = database.query(
            """
            SELECT plan_id, depot_code, service_date, attempts, row_version FROM ml.plan_scorings
             WHERE next_attempt_at <= ? AND (status = 'pending' OR (status = 'degraded' AND attempts < ?))
             ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            Timestamp.from(now), maxAttempts, limit)
        .stream()
        .map(r -> new DueScoring(
            (UUID) r.get("plan_id"), (String) r.get("depot_code"), ((Date) r.get("service_date")).toLocalDate(),
            ((Number) r.get("attempts")).intValue(), ((Number) r.get("row_version")).longValue() + 1))
        .toList();
    for (DueScoring d : due) {
      database.update(
          "UPDATE ml.plan_scorings SET next_attempt_at = ?, row_version = row_version + 1 WHERE plan_id = ?",
          Timestamp.from(leaseUntil), d.planId());
    }
    return due;
  }

  public void completeScoring(
      UUID planId, long expectedVersion, String status, Optional<UUID> modelVersionId, String modelLabel,
      Optional<String> roadConditions, Optional<String> reason, int attempts, Optional<Instant> nextAttemptAt,
      Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE ml.plan_scorings
           SET status = ?, model_version_id = ?, model_label = ?, road_conditions = ?, reason = ?, attempts = ?,
               next_attempt_at = ?, scored_at = ?, row_version = row_version + 1
         WHERE plan_id = ? AND row_version = ?
        """,
        status, modelVersionId.orElse(null), modelLabel, roadConditions.orElse(null), reason.orElse(null), attempts,
        nextAttemptAt.map(Timestamp::from).orElse(null), Timestamp.from(at), planId, expectedVersion);
  }

  public Optional<Map<String, Object>> scoring(UUID planId) {
    return Optional.ofNullable(database.queryOne(
        "SELECT plan_id, depot_code, service_date, status, model_label, road_conditions, reason, scored_at"
            + " FROM ml.plan_scorings WHERE plan_id = ?",
        planId));
  }

  public long pendingScorings() {
    return ((Number) database.queryOne("SELECT count(*) AS n FROM ml.plan_scorings WHERE status = 'pending'")
            .get("n"))
        .longValue();
  }

  public record Prediction(
      UUID planId, UUID orderId, UUID tripId, int sequence, String outletId, String depotCode,
      Optional<UUID> modelVersionId, String modelLabel, BigDecimal serviceMinutes, BigDecimal lateProbability,
      boolean degraded) {}

  public void insertPrediction(UUID id, Prediction p, Instant at) {
    database.update(
        """
        INSERT INTO ml.delivery_predictions
            (prediction_id, plan_id, order_id, trip_id, stop_sequence, outlet_id, depot_code, model_version_id,
             model_label, service_min, late_prob, degraded, generated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (plan_id, order_id, model_label) DO NOTHING
        """,
        id, p.planId(), p.orderId(), p.tripId(), p.sequence(), p.outletId(), p.depotCode(),
        p.modelVersionId().orElse(null), p.modelLabel(), p.serviceMinutes(), p.lateProbability(), p.degraded(),
        Timestamp.from(at));
  }

  /** A plan's predictions, the model's when it scored the plan, else the deterministic ones. */
  public List<Map<String, Object>> predictions(UUID planId) {
    return database.query(
        """
        SELECT order_id, trip_id, stop_sequence, outlet_id, model_label, service_min, late_prob, degraded
          FROM ml.delivery_predictions p
         WHERE plan_id = ?
           AND (NOT degraded OR NOT EXISTS (SELECT 1 FROM ml.delivery_predictions m
                                             WHERE m.plan_id = p.plan_id AND m.order_id = p.order_id AND NOT m.degraded))
         ORDER BY trip_id, stop_sequence
        """,
        planId);
  }

  // ---- forecasts ------------------------------------------------------------------

  public record Forecast(
      UUID runId, String depotCode, String brandCode, int isoYear, int isoWeek, BigDecimal totalM3,
      BigDecimal chilledM3, Optional<UUID> modelVersionId, String modelLabel, boolean degraded) {}

  public void insertForecast(UUID id, Forecast f, Instant at) {
    database.update(
        """
        INSERT INTO ml.demand_forecasts
            (forecast_id, run_id, depot_code, brand_code, iso_year, iso_week, total_m3, chilled_m3,
             model_version_id, model_label, degraded, generated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id, f.runId(), f.depotCode(), f.brandCode(), f.isoYear(), f.isoWeek(), f.totalM3(), f.chilledM3(),
        f.modelVersionId().orElse(null), f.modelLabel(), f.degraded(), Timestamp.from(at));
  }

  /** The newest forecast for each week in the range. */
  public List<Map<String, Object>> latestForecasts(String depot, String brand, int fromKey, int toKey) {
    return database.query(
        """
        SELECT DISTINCT ON (iso_year, iso_week) depot_code, brand_code, iso_year, iso_week, total_m3, chilled_m3,
               model_label, degraded
          FROM ml.demand_forecasts
         WHERE depot_code = ? AND brand_code = ? AND iso_year * 100 + iso_week BETWEEN ? AND ?
         ORDER BY iso_year, iso_week, generated_at DESC, forecast_id
        """,
        depot, brand, fromKey, toKey);
  }

  /** The newest forecast for every brand and week of a depot in the range, with when and by what. */
  public List<Map<String, Object>> latestDepotForecasts(String depot, int fromKey, int toKey) {
    return database.query(
        """
        SELECT DISTINCT ON (brand_code, iso_year, iso_week) brand_code, iso_year, iso_week, total_m3, chilled_m3,
               model_label, degraded, generated_at
          FROM ml.demand_forecasts
         WHERE depot_code = ? AND iso_year * 100 + iso_week BETWEEN ? AND ?
         ORDER BY brand_code, iso_year, iso_week, generated_at DESC, forecast_id
        """,
        depot, fromKey, toKey);
  }

  public record RunMark(Instant at, boolean degraded) {}

  /** The newest forecast run, as the process sees every depot: when, and whether a model answered. */
  public Optional<RunMark> latestForecastRun() {
    Map<String, Object> row = database.queryOne(
        "SELECT generated_at, degraded FROM ml.demand_forecasts ORDER BY generated_at DESC LIMIT 1");
    return Optional.ofNullable(row).map(r -> new RunMark(instant(r.get("generated_at")), (Boolean) r.get("degraded")));
  }

  // ---- mapping ----------------------------------------------------------------------

  private String write(Map<String, BigDecimal> metrics) {
    try {
      return json.writeValueAsString(metrics == null ? Map.of() : metrics);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("metrics are not serialisable", e);
    }
  }

  private Map<String, BigDecimal> read(String text) {
    try {
      return text == null ? Map.of() : json.readValue(text, METRICS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unreadable model metrics", e);
    }
  }

  private static Date date(LocalDate d) {
    return d == null ? null : Date.valueOf(d);
  }

  private static Optional<LocalDate> optionalDate(Object v) {
    return Optional.ofNullable((Date) v).map(Date::toLocalDate);
  }

  public static Instant instant(Object value) {
    if (value instanceof Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    throw new IllegalStateException("not a timestamp: " + value);
  }
}
