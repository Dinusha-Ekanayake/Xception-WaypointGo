package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelStatus;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ModelVersionView;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowReviewView;
import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.intelligence.domain.ModelRegistryPolicy;
import com.waypoint.dispatch.intelligence.domain.ShadowReview;
import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
          + " trained_to, registered_at, activated_at, retired_reason, row_version, shadow_role, shadowed_at"
          + " FROM ml.model_versions";

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
  public Optional<ModelRow> shadowModel(String kind) {
    return models(MODEL + " WHERE kind = ? AND status = 'shadow'", kind).stream().findFirst();
  }

  /** Every model of a kind that is not retired: what a registry move may change. */
  public List<ModelRow> standingOf(String kind) {
    return models(MODEL + " WHERE kind = ? AND status <> 'retired' ORDER BY registered_at", kind);
  }

  /**
   * Leaves the active or shadow standing first (issue #12): one active and one
   * shadow per kind are unique indexes checked per statement, so a promotion
   * first returns both to registered, then sets each where it goes.
   */
  public void vacate(UUID id, Instant at) {
    database.update(
        "UPDATE ml.model_versions SET status = 'registered', shadow_role = NULL, row_version = row_version + 1,"
            + " updated_at = ? WHERE model_version_id = ? AND status IN ('active', 'shadow')",
        Timestamp.from(at), id);
  }

  /**
   * One model to where a registry move puts it.
   *
   * @param expectedVersion the version the caller read, for the model the command names; empty for
   *     the other models the move changes, which the same serializable transaction read
   */
  public void move(UUID id, ModelRegistryPolicy.Move move, Optional<Long> expectedVersion, UUID by, Instant at) {
    String set = switch (move.to()) {
      case ACTIVE -> "status = 'active', shadow_role = NULL, activated_by = ?, activated_at = ?";
      case SHADOW -> "status = 'shadow', shadow_role = ?, shadowed_by = ?, shadowed_at = ?";
      case REGISTERED -> "status = 'registered', shadow_role = NULL";
      case RETIRED -> throw new IllegalArgumentException("retirement is its own command");
    };
    List<Object> params = new ArrayList<>();
    switch (move.to()) {
      case ACTIVE -> {
        params.add(by);
        params.add(Timestamp.from(at));
      }
      case SHADOW -> {
        params.add(move.role().orElseThrow().name().toLowerCase(Locale.ROOT));
        params.add(by);
        params.add(Timestamp.from(at));
      }
      default -> { }
    }
    params.add(Timestamp.from(at));
    params.add(id);
    String sql = "UPDATE ml.model_versions SET " + set + ", row_version = row_version + 1, updated_at = ?"
        + " WHERE model_version_id = ?";
    if (expectedVersion.isPresent()) {
      params.add(expectedVersion.get());
      database.updateExpectingOneRow(sql + " AND row_version = ?", params.toArray());
    } else {
      database.update(sql, params.toArray());
    }
  }

  public void retire(UUID id, long expectedVersion, UUID by, String reason, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE ml.model_versions SET status = 'retired', shadow_role = NULL, retired_by = ?, retired_at = ?,"
            + " retired_reason = ?, row_version = row_version + 1, updated_at = ?"
            + " WHERE model_version_id = ? AND row_version = ?",
        by, Timestamp.from(at), reason, Timestamp.from(at), id, expectedVersion);
  }

  /** Rule 8: who changed a model's standing, why and when (issue #12). */
  public void insertModelEvent(
      UUID eventId, UUID modelId, String action, UUID actor, Optional<String> reason, Optional<UUID> reviewId,
      Instant at) {
    database.update(
        """
        INSERT INTO ml.model_events (event_id, model_version_id, action, actor_id, reason, review_id, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        eventId, modelId, action, actor, reason.orElse(null), reviewId.orElse(null), Timestamp.from(at));
  }

  // ---- shadow scoring and its review (issue #12, R-ML-38) ------------------------------

  public boolean hasShadowPredictions(UUID planId, String label) {
    return database.queryOne(
            "SELECT 1 AS one FROM ml.shadow_predictions WHERE plan_id = ? AND model_label = ? LIMIT 1", planId, label)
        != null;
  }

  public void insertShadowPrediction(
      UUID id, UUID planId, UUID orderId, String depot, LocalDate serviceDate, UUID modelId, String label,
      BigDecimal serviceMinutes, BigDecimal lateProbability, Instant at) {
    database.update(
        """
        INSERT INTO ml.shadow_predictions
            (shadow_prediction_id, plan_id, order_id, depot_code, service_date, model_version_id, model_label,
             service_min, late_prob, generated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (plan_id, order_id, model_label) DO NOTHING
        """,
        id, planId, orderId, depot, Date.valueOf(serviceDate), modelId, label, serviceMinutes, lateProbability,
        Timestamp.from(at));
  }

  /**
   * Each labelled stop of the window that the shadow scored and the incumbent
   * answered: the shadow's newest prediction for the order beside the one the
   * outcome recorded as having answered.
   */
  public List<ShadowReview.Pair> shadowPairs(String shadowLabel, String incumbentLabel, LocalDate from, LocalDate to) {
    return database.query(
            """
            SELECT DISTINCT ON (o.delivery_id) s.late_prob AS shadow, o.predicted_late_prob AS incumbent, o.late_label
              FROM ml.delivery_outcomes o
              JOIN ml.shadow_predictions s ON s.order_id = o.order_id AND s.model_label = ?
             WHERE o.model_label = ? AND o.late_label IS NOT NULL AND o.predicted_late_prob IS NOT NULL
               AND o.service_date BETWEEN ? AND ?
             ORDER BY o.delivery_id, s.generated_at DESC
            """,
            shadowLabel, incumbentLabel, Date.valueOf(from), Date.valueOf(to))
        .stream()
        .map(r -> new ShadowReview.Pair(
            ((Number) r.get("shadow")).doubleValue(), ((Number) r.get("incumbent")).doubleValue(),
            (Boolean) r.get("late_label")))
        .toList();
  }

  /** One review per shadow model and day; a rerun the same day replaces the figures. Returns its id. */
  public UUID saveReview(
      UUID newId, String kind, UUID modelId, String label, ShadowRole role, String incumbent, LocalDate on,
      LocalDate from, LocalDate to, ShadowReview.Result r, Instant at) {
    Map<String, Object> row = database.queryOne(
        """
        INSERT INTO ml.shadow_reviews
            (review_id, kind, model_version_id, model_label, shadow_role, incumbent_label, reviewed_on, window_from,
             window_to, stops, shadow_brier, shadow_log_loss, incumbent_brier, incumbent_log_loss, verdict, reviewed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (model_version_id, reviewed_on) DO UPDATE SET
            incumbent_label = EXCLUDED.incumbent_label, window_from = EXCLUDED.window_from,
            window_to = EXCLUDED.window_to, stops = EXCLUDED.stops, shadow_brier = EXCLUDED.shadow_brier,
            shadow_log_loss = EXCLUDED.shadow_log_loss, incumbent_brier = EXCLUDED.incumbent_brier,
            incumbent_log_loss = EXCLUDED.incumbent_log_loss, verdict = EXCLUDED.verdict,
            reviewed_at = EXCLUDED.reviewed_at
        RETURNING review_id
        """,
        newId, kind, modelId, label, role.name().toLowerCase(Locale.ROOT), incumbent, Date.valueOf(on),
        Date.valueOf(from), Date.valueOf(to), r.stops(), figure(r.shadowBrier()), figure(r.shadowLogLoss()),
        figure(r.incumbentBrier()), figure(r.incumbentLogLoss()), r.verdict().name(), Timestamp.from(at));
    return (UUID) row.get("review_id");
  }

  public void markRolledBack(UUID reviewId) {
    database.update("UPDATE ml.shadow_reviews SET action = 'rolled_back' WHERE review_id = ?", reviewId);
  }

  /** The newest reviews first, at most {@code limit}. */
  public List<ShadowReviewView> reviews(int limit) {
    return database.query(
            """
            SELECT kind, model_label, shadow_role, incumbent_label, reviewed_on, window_from, window_to, stops,
                   shadow_brier, shadow_log_loss, incumbent_brier, incumbent_log_loss, verdict, action
              FROM ml.shadow_reviews ORDER BY reviewed_on DESC, model_label LIMIT ?
            """,
            limit)
        .stream()
        .map(r -> new ShadowReviewView(
            (String) r.get("kind"), (String) r.get("model_label"),
            ShadowRole.valueOf(((String) r.get("shadow_role")).toUpperCase(Locale.ROOT)),
            (String) r.get("incumbent_label"), ((Date) r.get("reviewed_on")).toLocalDate(),
            ((Date) r.get("window_from")).toLocalDate(), ((Date) r.get("window_to")).toLocalDate(),
            ((Number) r.get("stops")).intValue(), decimal(r.get("shadow_brier")), decimal(r.get("shadow_log_loss")),
            decimal(r.get("incumbent_brier")), decimal(r.get("incumbent_log_loss")), (String) r.get("verdict"),
            Optional.ofNullable((String) r.get("action"))))
        .toList();
  }

  private static BigDecimal figure(Optional<Double> value) {
    return value.filter(Double::isFinite)
        .map(v -> BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP))
        .orElse(null);
  }

  private static Optional<BigDecimal> decimal(Object value) {
    return Optional.ofNullable((BigDecimal) value);
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
                ((Number) r.get("row_version")).longValue(),
                Optional.ofNullable((String) r.get("shadow_role"))
                    .map(role -> ShadowRole.valueOf(role.toUpperCase(Locale.ROOT))),
                Optional.ofNullable(r.get("shadowed_at")).map(JdbcIntelligenceRepository::instant))))
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
      boolean degraded, Optional<com.waypoint.dispatch.intelligence.domain.ServiceRange.Range> serviceRange) {}

  public void insertPrediction(UUID id, Prediction p, Instant at) {
    database.update(
        """
        INSERT INTO ml.delivery_predictions
            (prediction_id, plan_id, order_id, trip_id, stop_sequence, outlet_id, depot_code, model_version_id,
             model_label, service_min, late_prob, degraded, generated_at, service_low, service_high,
             service_range_basis)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (plan_id, order_id, model_label) DO NOTHING
        """,
        id, p.planId(), p.orderId(), p.tripId(), p.sequence(), p.outletId(), p.depotCode(),
        p.modelVersionId().orElse(null), p.modelLabel(), p.serviceMinutes(), p.lateProbability(), p.degraded(),
        Timestamp.from(at),
        p.serviceRange().map(r -> BigDecimal.valueOf(r.low())).orElse(null),
        p.serviceRange().map(r -> BigDecimal.valueOf(r.high())).orElse(null),
        p.serviceRange().map(r -> r.basis().name().toLowerCase(java.util.Locale.ROOT)).orElse(null));
  }

  /** A plan's predictions, the model's when it scored the plan, else the deterministic ones. */
  public List<Map<String, Object>> predictions(UUID planId) {
    return database.query(
        """
        SELECT order_id, trip_id, stop_sequence, outlet_id, model_label, service_min, late_prob, degraded,
               service_low, service_high
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

  /** The warning already given for an order's day, if any (R-ML-08). */
  public Optional<String> warnedOutlook(UUID orderId, LocalDate deliveryDate) {
    return database.query(
            "SELECT status FROM ml.order_outlooks WHERE order_id = ? AND delivery_date = ?",
            orderId, java.sql.Date.valueOf(deliveryDate))
        .stream().findFirst().map(r -> (String) r.get("status"));
  }

  /** Records the warning now given; a worse one replaces a milder one. */
  public void recordWarning(
      UUID orderId, LocalDate deliveryDate, String outletId, String depotCode, String status, String reason,
      Instant at) {
    database.update(
        """
        INSERT INTO ml.order_outlooks (order_id, delivery_date, outlet_id, depot_code, status, reason, notified_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (order_id, delivery_date) DO UPDATE
           SET status = EXCLUDED.status, reason = EXCLUDED.reason, notified_at = EXCLUDED.notified_at
        """,
        orderId, java.sql.Date.valueOf(deliveryDate), outletId, depotCode, status, reason,
        java.sql.Timestamp.from(at));
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
