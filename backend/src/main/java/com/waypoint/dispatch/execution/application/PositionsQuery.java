package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionViews.TrailPointView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.VehiclePositionView;
import com.waypoint.dispatch.execution.domain.PositionFix;
import com.waypoint.dispatch.execution.domain.PositionPolicy;
import com.waypoint.dispatch.execution.infrastructure.JdbcExecutionReads;
import com.waypoint.dispatch.execution.infrastructure.JdbcPositions;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Where the vehicles are (issue #161). Scope is decided in SQL: the depot and
 * outlet checks refuse out-of-scope requests with an audited 403, and row-level
 * security decides which points remain, including the store's visibility ending
 * when its stop is done (R-EXE-20).
 */
@Component
public class PositionsQuery {
  private static final String READ = ExecutionDataQuery.READ;

  private final Database database;
  private final JdbcPositions positions;
  private final JdbcExecutionReads reads;
  private final AuditLog audit;
  private final Clock clock;

  public PositionsQuery(
      Database database, JdbcPositions positions, JdbcExecutionReads reads, AuditLog audit, Clock clock) {
    this.database = database;
    this.positions = positions;
    this.reads = reads;
    this.audit = audit;
    this.clock = clock;
  }

  public List<VehiclePositionView> ofDepot(Actor actor, String depotCode, LocalDate serviceDate) {
    require(actor, "wpt:execution:depot:" + depotCode, () -> reads.depotInScope(depotCode));
    return latest(read(actor, () -> positions.latestOfDepot(depotCode, serviceDate)));
  }

  public List<VehiclePositionView> forOutlet(Actor actor, String outletId, LocalDate serviceDate) {
    require(actor, "wpt:execution:outlet:" + outletId, () -> reads.outletInScope(outletId));
    return latest(read(actor, () -> positions.latestForOutlet(outletId, serviceDate)));
  }

  public Page<TrailPointView> trail(Actor actor, UUID tripId, Optional<String> cursor, Integer limit) {
    return trail(actor, tripId, cursor, Optional.empty(), limit);
  }

  /** @param since exclusive: only points recorded after it, for a map that already holds the rest */
  public Page<TrailPointView> trail(
      Actor actor, UUID tripId, Optional<String> cursor, Optional<Instant> since, Integer limit) {
    require(actor, "wpt:execution:trip:" + tripId, () -> positions.tripVisible(tripId));
    int size = Page.limit(limit);
    List<String> key = Cursor.decode(cursor.orElse(null), 1);
    Instant after;
    try {
      after = key.isEmpty() ? since.orElse(null) : Instant.parse(key.get(0));
    } catch (RuntimeException e) {
      throw Cursor.invalid();
    }
    List<TrailPointView> rows =
        read(actor, () -> positions.trail(tripId, after, size + 1)).stream()
            .map(r -> new TrailPointView(
                ((Timestamp) r.get("recorded_at")).toInstant(), (BigDecimal) r.get("latitude"),
                (BigDecimal) r.get("longitude"), (Boolean) r.get("low_quality")))
            .toList();
    return Page.fromOverfetch(rows, size, p -> Cursor.encode(p.recordedAt().toString()));
  }

  /**
   * One view per vehicle from its recent fixes (rows grouped by vehicle, newest
   * first): the newest is where it is, and the heading is its direction of
   * travel from the fixes before it (R-EXE-22), not the phone's compass.
   */
  private List<VehiclePositionView> latest(List<Map<String, Object>> rows) {
    Instant now = clock.now();
    Map<String, List<Map<String, Object>>> byVehicle = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      byVehicle.computeIfAbsent((String) r.get("vehicle_id"), k -> new ArrayList<>()).add(r);
    }
    List<VehiclePositionView> views = new ArrayList<>();
    byVehicle.forEach((vehicleId, recent) -> {
      List<PositionFix> fixes = recent.stream().map(PositionsQuery::fixOf).toList();
      Map<String, Object> r = recent.get(0);
      PositionFix fix = fixes.get(0);
      views.add(new VehiclePositionView(
          vehicleId, Optional.ofNullable((UUID) r.get("trip_id")), fix.latitude(), fix.longitude(),
          PositionPolicy.travelHeading(fixes), Optional.ofNullable(fix.accuracyM()), fix.recordedAt(),
          PositionPolicy.isOffline(fix, now, Boolean.TRUE.equals(r.get("in_progress")))));
    });
    return List.copyOf(views);
  }

  private static PositionFix fixOf(Map<String, Object> r) {
    return new PositionFix(
        ((Timestamp) r.get("recorded_at")).toInstant(), (BigDecimal) r.get("latitude"),
        (BigDecimal) r.get("longitude"), (BigDecimal) r.get("accuracy_m"), (BigDecimal) r.get("heading_deg"), null);
  }

  private <T> T read(Actor actor, Supplier<T> work) {
    return database.readAs(ModuleRole.EXECUTION, actor.userId(), work);
  }

  private void require(Actor actor, String resource, Supplier<Boolean> inScope) {
    if (!read(actor, inScope)) {
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }
}
