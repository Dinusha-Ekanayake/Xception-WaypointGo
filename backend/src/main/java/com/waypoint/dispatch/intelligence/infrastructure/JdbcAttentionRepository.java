package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionItemView;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionKind;
import com.waypoint.dispatch.intelligence.contract.AttentionViews.AttentionSeverity;
import com.waypoint.dispatch.intelligence.domain.AttentionThresholds;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The attention watch in PostgreSQL (issue #268). Always inside a transaction
 * someone else opened, as {@code waypoint_ml}, so row-level security has
 * already narrowed reads to the actor's depots (or everything, for the watch).
 */
@Repository
public class JdbcAttentionRepository {
  private final Database database;

  public JdbcAttentionRepository(Database database) {
    this.database = database;
  }

  /** A depot's own thresholds, or the defaults when it has set none. */
  public AttentionThresholds thresholds(String depotCode) {
    return database.query(
            """
            SELECT late_minutes, window_warn_minutes, proof_grace_minutes, remind_after_minutes, max_reminders
              FROM ml.attention_thresholds WHERE depot_code = ?
            """,
            depotCode)
        .stream().findFirst()
        .map(r -> new AttentionThresholds(
            number(r, "late_minutes"), number(r, "window_warn_minutes"), number(r, "proof_grace_minutes"),
            number(r, "remind_after_minutes"), number(r, "max_reminders")))
        .orElse(AttentionThresholds.DEFAULT);
  }

  /**
   * Raises the item, or refreshes it when it is already known. An item that
   * had cleared and is back is open again; one already acknowledged stays so.
   *
   * @return true when the item is new
   */
  public boolean raise(
      UUID deliveryId, AttentionKind kind, AttentionSeverity severity, String depotCode, LocalDate serviceDate,
      String vehicleId, UUID tripId, String outletId, Optional<Integer> minutesLeft, Instant now) {
    Timestamp at = Timestamp.from(now);
    Map<String, Object> row = database.queryOne(
        """
        INSERT INTO ml.attention_items
            (delivery_id, kind, severity, depot_code, service_date, vehicle_id, trip_id, outlet_id,
             minutes_left, raised_at, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (delivery_id, kind) DO UPDATE
           SET severity = EXCLUDED.severity,
               minutes_left = EXCLUDED.minutes_left,
               last_seen_at = EXCLUDED.last_seen_at,
               cleared_at = NULL,
               row_version = ml.attention_items.row_version + 1
        RETURNING (xmax = 0) AS inserted
        """,
        deliveryId, kind.name(), severity.name(), depotCode, Date.valueOf(serviceDate), vehicleId, tripId, outletId,
        minutesLeft.orElse(null), at, at);
    return Boolean.TRUE.equals(row.get("inserted"));
  }

  /** Ends every open item of the depot and day that this run did not see again. */
  public int clearUnseen(String depotCode, LocalDate serviceDate, Instant now) {
    return database.update(
        """
        UPDATE ml.attention_items
           SET cleared_at = ?, row_version = row_version + 1
         WHERE depot_code = ? AND service_date = ? AND cleared_at IS NULL AND last_seen_at < ?
        """,
        Timestamp.from(now), depotCode, Date.valueOf(serviceDate), Timestamp.from(now));
  }

  public void beat(String depotCode, int vehicles, Instant now) {
    database.update(
        """
        INSERT INTO ml.attention_heartbeats (depot_code, checked_at, vehicles) VALUES (?, ?, ?)
        ON CONFLICT (depot_code) DO UPDATE SET checked_at = EXCLUDED.checked_at, vehicles = EXCLUDED.vehicles
        """,
        depotCode, Timestamp.from(now), vehicles);
  }

  public Optional<Instant> checkedAt(String depotCode) {
    return database.query("SELECT checked_at FROM ml.attention_heartbeats WHERE depot_code = ?", depotCode)
        .stream().findFirst().map(r -> JdbcIntelligenceRepository.instant(r.get("checked_at")));
  }

  /** Open items, most urgent first: by severity, then the window closing soonest, then the oldest. */
  public List<AttentionItemView> open(String depotCode, LocalDate serviceDate) {
    return database.query(
            """
            SELECT delivery_id, kind, severity, depot_code, service_date, vehicle_id, trip_id, outlet_id,
                   minutes_left, raised_at, reminded_count, row_version
              FROM ml.attention_items
             WHERE depot_code = ? AND service_date = ? AND cleared_at IS NULL AND acknowledged_at IS NULL
             ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END,
                      minutes_left NULLS LAST, raised_at, delivery_id
            """,
            depotCode, Date.valueOf(serviceDate))
        .stream()
        .map(r -> new AttentionItemView(
            (UUID) r.get("delivery_id"),
            AttentionKind.valueOf((String) r.get("kind")),
            AttentionSeverity.valueOf((String) r.get("severity")),
            (String) r.get("depot_code"),
            ((Date) r.get("service_date")).toLocalDate(),
            (String) r.get("vehicle_id"),
            (UUID) r.get("trip_id"),
            (String) r.get("outlet_id"),
            Optional.ofNullable((Number) r.get("minutes_left")).map(Number::intValue),
            JdbcIntelligenceRepository.instant(r.get("raised_at")),
            number(r, "reminded_count"),
            ((Number) r.get("row_version")).longValue()))
        .toList();
  }

  private static int number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).intValue();
  }
}
