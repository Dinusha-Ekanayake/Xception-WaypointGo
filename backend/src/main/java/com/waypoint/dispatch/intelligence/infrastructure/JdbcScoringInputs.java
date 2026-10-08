package com.waypoint.dispatch.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Each scored route as the model server read it (roadmap MF-05, R-ML-18), and
 * the training read that joins it to the outcomes of the plan that ran.
 */
@Component
public class JdbcScoringInputs {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

  private final Database database;
  private final ObjectMapper json;

  public JdbcScoringInputs(Database database, ObjectMapper json) {
    this.database = database;
    this.json = json;
  }

  /** A retried scoring keeps the first copy: the inputs as at publication. */
  public void record(UUID planId, String routeId, String depot, LocalDate day, Optional<UUID> referenceVersionId,
      Map<String, Object> route, Instant at) {
    database.update(
        """
        INSERT INTO ml.scoring_inputs
            (plan_id, route_id, depot_code, service_date, reference_version_id, route, recorded_at)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (plan_id, route_id) DO NOTHING
        """,
        planId, routeId, depot, Date.valueOf(day), referenceVersionId.orElse(null), write(route), Timestamp.from(at));
  }

  /** One training row: the route as scored and each stop's outcome of the plan that ran. */
  public record Row(UUID planId, String routeId, LocalDate serviceDate, Optional<UUID> referenceVersionId,
      Map<String, Object> route, List<Map<String, Object>> labels) {}

  /**
   * Routes of the depot's days whose stops have outcomes, keyed on (day, plan,
   * route). Only the plan whose predictions the outcomes point at counts, so a
   * plan that was republished before the day trains once, as it ran.
   */
  public List<Row> page(String depot, LocalDate from, LocalDate to, Optional<List<String>> after, int limit) {
    String keyset = after.isPresent() ? " AND (s.service_date, s.plan_id, s.route_id) > (?::date, ?::uuid, ?)" : "";
    Object[] args = after.isPresent()
        ? new Object[] {depot, Date.valueOf(from), Date.valueOf(to), after.get().get(0), after.get().get(1),
            after.get().get(2), limit + 1}
        : new Object[] {depot, Date.valueOf(from), Date.valueOf(to), limit + 1};
    return database.query(
        """
        SELECT s.plan_id, s.route_id, s.service_date, s.reference_version_id, s.route::text AS route,
               l.labels::text AS labels
          FROM ml.scoring_inputs s
          JOIN LATERAL (
                SELECT jsonb_agg(jsonb_build_object(
                           'deliveryId', o.order_id::text, 'outcome', o.outcome, 'late', o.late_label,
                           'labelSource', o.label_source, 'lateMinutes', o.late_minutes,
                           'serviceMinutes', o.service_minutes) ORDER BY o.stop_sequence) AS labels
                  FROM ml.delivery_outcomes o
                  JOIN ml.delivery_predictions p ON p.prediction_id = o.prediction_id
                 WHERE p.plan_id = s.plan_id AND p.trip_id::text = s.route_id) l ON l.labels IS NOT NULL
         WHERE s.depot_code = ? AND s.service_date BETWEEN ? AND ?
        """ + keyset + """
         ORDER BY s.service_date, s.plan_id, s.route_id
         LIMIT ?
        """,
        args).stream()
        .map(r -> new Row((UUID) r.get("plan_id"), (String) r.get("route_id"),
            ((Date) r.get("service_date")).toLocalDate(), Optional.ofNullable((UUID) r.get("reference_version_id")),
            read((String) r.get("route"), MAP), read((String) r.get("labels"), LIST)))
        .toList();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a scored route could not be written as JSON", e);
    }
  }

  private <T> T read(String text, TypeReference<T> type) {
    try {
      return json.readValue(text, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a stored scored route could not be read", e);
    }
  }
}
