package com.waypoint.dispatch.platform.audit;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Append only, and written in the same transaction as the change it describes,
 * so an audit gap is impossible rather than merely unlikely.
 *
 * <p>Denials are recorded as well as grants. A `403` that leaves no trace is how
 * an access problem becomes invisible.
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
            (actor_id, device_id, action, resource, decision, reason, correlation_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        entry.actorId(),
        entry.deviceId(),
        entry.action(),
        entry.resource(),
        entry.decision(),
        entry.reason(),
        entry.correlationId() != null ? entry.correlationId() : MDC.get("correlationId"));
  }

  /** For denials raised outside a business transaction, such as at the edge. */
  public void recordStandalone(AuditEntry entry) {
    database.asModule(ModuleRole.INTEGRATION, entry.actorId(), () -> record(entry));
  }
}
