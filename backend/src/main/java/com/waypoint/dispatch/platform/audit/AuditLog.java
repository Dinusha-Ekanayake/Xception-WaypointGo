package com.waypoint.dispatch.platform.audit;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Append only, and written in the same transaction as the change it describes,
 * so an audit gap is impossible rather than merely unlikely.
 *
 * <p>Denials are recorded as well as grants. A `403` that leaves no trace is how
 * an access problem becomes invisible.
 *
 * <p>The correlation id is the entry's own: the command bus passes the one the
 * request carried. Only an entry written by older call sites, which have no
 * request to take it from, falls back to the logging context, in
 * {@link #correlationOf}, the one place that still reads it.
 */
@Component
public class AuditLog {
  private final Database database;

  public AuditLog(Database database) {
    this.database = database;
  }

  /** Must be called inside an existing transaction, never on its own. */
  public void record(AuditEntry entry) {
    database.update(
        """
        INSERT INTO integration.audit_log
            (actor_id, device_id, action, resource, decision, reason, correlation_id,
             command_id, target_type, target_id, before_state, after_state, policy_generation)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
        """,
        entry.actorId(),
        entry.deviceId(),
        entry.action(),
        entry.resource(),
        entry.decision(),
        entry.reason(),
        correlationOf(entry),
        entry.commandId(),
        entry.targetType(),
        entry.targetId(),
        entry.beforeState(),
        entry.afterState(),
        entry.policyGeneration());
  }

  /** For denials raised outside a business transaction, such as at the edge. */
  public void recordStandalone(AuditEntry entry) {
    database.asModule(ModuleRole.INTEGRATION, entry.actorId(), () -> record(entry));
  }

  private static String correlationOf(AuditEntry entry) {
    return entry.correlationId() != null
        ? entry.correlationId()
        : MDC.get(CorrelationIdFilter.MDC_KEY);
  }
}
