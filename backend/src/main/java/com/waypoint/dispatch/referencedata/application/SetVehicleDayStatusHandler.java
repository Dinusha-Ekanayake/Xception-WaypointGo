package com.waypoint.dispatch.referencedata.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Marks a vehicle available, in the workshop, or otherwise unavailable for a day.
 *
 * <p>R-FLT-04: a status change affects the next planning run, never retroactively.
 * A truck that breaks at noon does not rewrite the morning's plan, which already
 * happened.
 *
 * <p>Reached only through the command bus, so the change, its receipt and its
 * audit row commit together and a retry from a depot with bad signal applies once.
 */
@Component
public class SetVehicleDayStatusHandler implements CommandHandler {
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

  @Override
  public String kind() {
    return "vehicle:SetDayStatus";
  }

  @Override
  public String action() {
    return "vehicle:SetDayStatus";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.REF;
  }

  /**
   * Best effort: a payload with no vehicle has no resource, so only an unscoped
   * grant reaches the handler, which then rejects it as malformed. Naming the
   * vehicle here is what lets a policy be written against one depot's fleet.
   */
  @Override
  public String resource(Command command) {
    String vehicleId = text(command.payload(), "vehicleId");
    return vehicleId == null ? null : "wpt:ref:vehicle:" + vehicleId;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    JsonNode payload = command.payload();
    String vehicleId = required(payload, "vehicleId");
    String status = required(payload, "status");
    LocalDate date = date(payload, "serviceDate");
    String reason = text(payload, "reason");

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

    // No transaction is opened here. The bus already did, as waypoint_ref, with
    // this actor set for row-level security.
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
            action(),
            "wpt:ref:vehicle:" + vehicleId,
            status + (reason == null ? "" : ": " + reason)));
    metrics.increment("waypoint.vehicle.status_changed", "status", status);

    return Map.of("vehicleId", vehicleId, "serviceDate", date.toString(), "status", status);
  }

  private static String required(JsonNode payload, String field) {
    String value = text(payload, field);
    if (value == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required");
    }
    return value;
  }

  private static String text(JsonNode payload, String field) {
    if (payload == null || !payload.hasNonNull(field)) {
      return null;
    }
    String value = payload.get(field).asText();
    return value.isBlank() ? null : value;
  }

  private static LocalDate date(JsonNode payload, String field) {
    String value = required(payload, field);
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be a date as yyyy-mm-dd, not " + value);
    }
  }
}
