package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Marks a vehicle available, in the workshop, or otherwise unavailable for a day.
 *
 * <p>R-FLT-04: a status change affects the next planning run, never retroactively.
 * A truck that breaks at noon does not rewrite the morning's plan, which already
 * happened.
 */
@Component
public class SetVehicleDayStatusHandler {
  private static final Set<String> STATUSES = Set.of("available", "in_workshop", "unavailable");

  private final Database database;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Metrics metrics;

  public SetVehicleDayStatusHandler(
      Database database, ReferenceQuery reference, AuditLog audit, Metrics metrics) {
    this.database = database;
    this.reference = reference;
    this.audit = audit;
    this.metrics = metrics;
  }

  public void handle(Actor actor, String vehicleId, LocalDate date, String status, String reason) {
    if (!STATUSES.contains(status)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Unknown vehicle status " + status + "; expected one of " + STATUSES);
    }
    if (reference.vehicle(vehicleId, null).isEmpty()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "Unknown vehicle " + vehicleId);
    }
    // Setting a status for a day the network does not operate is a mistake, not
    // a fact: nothing runs that day to be affected by it.
    if (!reference.isOperating(date)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, date + " is not an operating day", List.of("R-FLT-04"));
    }

    database.asModule(
        ModuleRole.REF,
        actor.userId(),
        () -> {
          database.update(
              """
              INSERT INTO ref.vehicle_day_status (vehicle_id, service_date, status, reason, set_by)
              VALUES (?, ?, ?, ?, ?)
              ON CONFLICT (vehicle_id, service_date)
              DO UPDATE SET status = EXCLUDED.status,
                            reason = EXCLUDED.reason,
                            set_by = EXCLUDED.set_by,
                            set_at = now()
              """,
              vehicleId,
              java.sql.Date.valueOf(date),
              status,
              reason,
              actor.userId());
          audit.record(
              AuditEntry.allowed(
                  actor.userId(),
                  actor.deviceId(),
                  "vehicle:SetDayStatus",
                  "wpt:ref:vehicle:" + vehicleId,
                  status + (reason == null ? "" : ": " + reason)));
        });
    metrics.increment("waypoint.vehicle.status_changed", "status", status);
  }
}
