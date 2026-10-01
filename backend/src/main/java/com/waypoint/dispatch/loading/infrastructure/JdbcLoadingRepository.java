package com.waypoint.dispatch.loading.infrastructure;

import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.domain.ItemLine;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.domain.LoadingSession.Holder;
import com.waypoint.dispatch.loading.domain.LoadingSession.Phase;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Loading's writes, and the load of one trip's session for a command.
 *
 * <p>Never opens a transaction: the command bus or the relay already did, as
 * waypoint_loading, with the actor set for row-level security. A trip outside
 * the actor's depots is simply not found here.
 */
@Repository
public class JdbcLoadingRepository {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Database database;

  public JdbcLoadingRepository(Database database) {
    this.database = database;
  }

  /** The current plan version of a trip, with depot and departure, for scope and events. */
  public record TripHeader(
      UUID tripId,
      int planVersion,
      UUID planId,
      String depotCode,
      java.time.LocalDate serviceDate,
      String vehicleId,
      String temperature) {}

  /** What a release writes beside the status. */
  public record Release(UUID by, Instant at) {}

  public boolean depotInScope(String depotCode) {
    return Boolean.TRUE.equals(database.queryOne(
        "SELECT (app.actor_is_system() OR app.actor_has_depot(?)) AS allowed", depotCode).get("allowed"));
  }

  public Optional<TripHeader> header(UUID tripId) {
    Map<String, Object> row =
        database.queryOne(
            """
            SELECT trip_id, plan_version, plan_id, depot_code, service_date, vehicle_id, temperature
            FROM loading.trips WHERE trip_id = ? AND superseded_at IS NULL
            """,
            tripId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new TripHeader(
            (UUID) row.get("trip_id"),
            ((Number) row.get("plan_version")).intValue(),
            (UUID) row.get("plan_id"),
            (String) row.get("depot_code"),
            ((java.sql.Date) row.get("service_date")).toLocalDate(),
            (String) row.get("vehicle_id"),
            (String) row.get("temperature")));
  }

  /** The session of a trip on its current plan version, with each item line's latest check. */
  public Optional<LoadingSession> session(UUID tripId) {
    Map<String, Object> s =
        database.queryOne(
            """
            SELECT s.trip_id, s.status, s.holder_user_id, s.holder_name, s.holder_code, s.held_since,
                   s.holder_active_at, s.row_version, t.temperature
            FROM loading.sessions s
            JOIN loading.trips t ON t.trip_id = s.trip_id AND t.superseded_at IS NULL
            WHERE s.trip_id = ?
            """,
            tripId);
    if (s == null) {
      return Optional.empty();
    }
    List<ItemLine> items = new ArrayList<>();
    for (Map<String, Object> r : itemRows(tripId)) {
      items.add(
          new ItemLine(
              (UUID) r.get("order_id"),
              ((Number) r.get("line_no")).intValue(),
              ((Number) r.get("stop_sequence")).intValue(),
              (String) r.get("product_id"),
              ((Number) r.get("units")).intValue(),
              r.get("status") == null ? CheckStatus.PENDING : status(r.get("status")),
              r.get("attempt") == null ? 0 : ((Number) r.get("attempt")).intValue(),
              r.get("loaded_units") == null ? 0 : ((Number) r.get("loaded_units")).intValue()));
    }
    Optional<Holder> holder =
        s.get("holder_user_id") == null
            ? Optional.empty()
            : Optional.of(
                new Holder(
                    (UUID) s.get("holder_user_id"),
                    (String) s.get("holder_name"),
                    Optional.ofNullable((String) s.get("holder_code")),
                    ((Timestamp) s.get("held_since")).toInstant(),
                    ((Timestamp) s.get("holder_active_at")).toInstant()));
    return Optional.of(
        new LoadingSession(
            tripId,
            phase((String) s.get("status")),
            holder,
            "chilled".equals(s.get("temperature")),
            items,
            ((Number) s.get("row_version")).longValue()));
  }

  /** Item lines of the current plan version with their latest attempt, in loading order. */
  public List<Map<String, Object>> itemRows(UUID tripId) {
    return database.query(
        """
        SELECT i.order_id, i.line_no, i.product_id, i.units, st.stop_sequence,
               c.status, c.attempt, c.units AS loaded_units, c.recorded_at, c.actor_user_id
        FROM loading.trips t
        JOIN loading.items i ON i.trip_id = t.trip_id AND i.plan_version = t.plan_version
        JOIN loading.stops st
          ON st.trip_id = i.trip_id AND st.plan_version = i.plan_version AND st.order_id = i.order_id
        LEFT JOIN LATERAL (
            SELECT ic.status, ic.attempt, ic.units, ic.recorded_at, ic.actor_user_id
            FROM loading.item_checks ic
            WHERE ic.trip_id = i.trip_id AND ic.plan_version = i.plan_version
              AND ic.order_id = i.order_id AND ic.line_no = i.line_no
            ORDER BY ic.attempt DESC LIMIT 1) c ON true
        WHERE t.trip_id = ? AND t.superseded_at IS NULL
        ORDER BY st.stop_sequence DESC, i.order_id, i.line_no
        """,
        tripId);
  }

  /** The stops of the current plan version in delivery order, for trip.released. */
  public List<ReleasedStop> releasedStops(UUID tripId) {
    return database
        .query(
            """
            SELECT st.stop_sequence, st.order_id, st.outlet_id, st.planned_arrival
            FROM loading.trips t
            JOIN loading.stops st ON st.trip_id = t.trip_id AND st.plan_version = t.plan_version
            WHERE t.trip_id = ? AND t.superseded_at IS NULL
            ORDER BY st.stop_sequence, st.order_id
            """,
            tripId)
        .stream()
        .map(
            r ->
                new ReleasedStop(
                    ((Number) r.get("stop_sequence")).intValue(),
                    (UUID) r.get("order_id"),
                    (String) r.get("outlet_id"),
                    r.get("planned_arrival") == null
                        ? null
                        : ((java.sql.Time) r.get("planned_arrival")).toLocalTime()))
        .toList();
  }

  /**
   * Writes the session after a command and moves its version on by one.
   *
   * @return the new row version
   */
  public long updateSession(
      LoadingSession next, long expectedVersion, Instant at, Optional<Release> release) {
    Optional<Holder> h = next.holder();
    database.updateExpectingOneRow(
        """
        UPDATE loading.sessions
           SET status = ?, holder_user_id = ?, holder_name = ?, holder_code = ?, held_since = ?,
               holder_active_at = ?,
               started_at = coalesce(started_at, CASE WHEN ? = 'in_progress' THEN ?::timestamptz END),
               released_at = ?, released_by = ?,
               row_version = row_version + 1, updated_at = ?
         WHERE trip_id = ? AND row_version = ?
        """,
        code(next.phase()),
        h.map(Holder::userId).orElse(null),
        h.map(Holder::name).orElse(null),
        h.flatMap(Holder::employeeCode).orElse(null),
        h.map(x -> Timestamp.from(x.since())).orElse(null),
        h.map(x -> Timestamp.from(x.lastActive())).orElse(null),
        code(next.phase()),
        Timestamp.from(at),
        release.map(r -> Timestamp.from(r.at())).orElse(null),
        release.map(Release::by).orElse(null),
        Timestamp.from(at),
        next.tripId(),
        expectedVersion);
    return expectedVersion + 1;
  }

  /** One new attempt per changed line. Earlier attempts stay as the custody record. */
  public void appendChecks(
      UUID tripId,
      int planVersion,
      List<ItemLine> changed,
      Optional<String> reason,
      Optional<UUID> shortfallId,
      UUID actor,
      UUID deviceId,
      UUID commandId,
      Instant at,
      Instant clientRecordedAt) {
    for (ItemLine line : changed) {
      database.update(
          """
          INSERT INTO loading.item_checks
              (check_id, trip_id, plan_version, order_id, line_no, attempt, status, units, reason,
               shortfall_id, actor_user_id, device_id, command_id, recorded_at, client_recorded_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          UuidV7.generate(at, RANDOM),
          tripId,
          planVersion,
          line.orderId(),
          line.lineNo(),
          line.attempt(),
          code(line.status()),
          line.loadedUnits(),
          reason.orElse(null),
          shortfallId.orElse(null),
          actor,
          deviceId,
          commandId,
          Timestamp.from(at),
          clientRecordedAt == null ? null : Timestamp.from(clientRecordedAt));
    }
  }

  public void insertShortfall(
      UUID shortfallId,
      UUID tripId,
      int planVersion,
      UUID orderId,
      Optional<Integer> lineNo,
      CheckStatus kind,
      int missingUnits,
      String reason,
      Optional<UUID> photo,
      UUID actor,
      UUID deviceId,
      Instant at) {
    database.update(
        """
        INSERT INTO loading.shortfalls
            (shortfall_id, trip_id, plan_version, order_id, line_no, kind, missing_units, reason,
             photo_attachment_id, reported_by, device_id, reported_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        shortfallId,
        tripId,
        planVersion,
        orderId,
        lineNo.orElse(null),
        code(kind),
        missingUnits,
        reason,
        photo.orElse(null),
        actor,
        deviceId,
        Timestamp.from(at));
  }

  public static String code(Phase phase) {
    return switch (phase) {
      case NOT_STARTED -> "not_started";
      case IN_PROGRESS -> "in_progress";
      case RELEASED -> "released";
    };
  }

  public static Phase phase(String code) {
    return switch (code) {
      case "not_started" -> Phase.NOT_STARTED;
      case "in_progress" -> Phase.IN_PROGRESS;
      case "released" -> Phase.RELEASED;
      default -> throw new IllegalStateException("Unknown session status " + code);
    };
  }

  public static String code(CheckStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }

  public static CheckStatus status(Object code) {
    return CheckStatus.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }
}
