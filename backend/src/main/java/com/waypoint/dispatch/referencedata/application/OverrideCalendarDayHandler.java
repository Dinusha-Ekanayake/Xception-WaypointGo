package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceVersionReader;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A person overruling the calendar for one day.
 *
 * <p>R-CAL-03's other half. Generation keeps planning working past the end of the
 * supplied calendar; this is a declaration that a specific day does or does not
 * operate, whatever the data says: an unscheduled shutdown, or a Sunday the
 * network works because a festival moved.
 *
 * <p>A reason is required and stored, because an override with no reason is
 * indistinguishable from a mistake six months later. The override lives in its own
 * table so a reference import cannot quietly discard it.
 */
@Component
public class OverrideCalendarDayHandler implements CommandHandler {

  private final Database database;
  private final ReferenceVersionReader reader;
  private final ReferenceBootstrap bootstrap;
  private final AuditLog audit;
  private final Metrics metrics;

  public OverrideCalendarDayHandler(
      Database database,
      ReferenceVersionReader reader,
      ReferenceBootstrap bootstrap,
      AuditLog audit,
      Metrics metrics) {
    this.database = database;
    this.reader = reader;
    this.bootstrap = bootstrap;
    this.audit = audit;
    this.metrics = metrics;
  }

  @Override
  public String kind() {
    return "calendar:Override";
  }

  @Override
  public String action() {
    return "calendar:Override";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.REF;
  }

  @Override
  public String resource(Command command) {
    LocalDate date = CommandPayload.of(command).optionalDate("date");
    return date == null ? null : "wpt:ref:calendar:" + date;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    LocalDate date = payload.date("date");
    boolean operating = payload.flag("operating", false);
    String reason = payload.requiredText("reason");
    if (reason.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A reason of at least three characters is required");
    }

    database.update(
        """
        INSERT INTO ref.calendar_overrides (calendar_date, is_operating, reason, set_by)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (calendar_date)
        DO UPDATE SET is_operating = EXCLUDED.is_operating,
                      reason = EXCLUDED.reason,
                      set_by = EXCLUDED.set_by,
                      set_at = now()
        """,
        java.sql.Date.valueOf(date),
        operating,
        reason,
        actor.userId());

    // F-10: reference is read from memory, so the edit bumps the generation in
    // this transaction and the snapshot is rebuilt once it commits: here at once,
    // on every other process within the bootstrap's check, never ahead of the
    // database. Planning keys its cache on the generation too.
    reader.bumpGeneration();
    bootstrap.reloadAfterCommit();

    audit.record(
        AuditEntry.allowed(
            actor.userId(),
            actor.deviceId(),
            action(),
            "wpt:ref:calendar:" + date,
            (operating ? "operating: " : "not operating: ") + reason));
    metrics.increment("waypoint.calendar.overridden", "operating", String.valueOf(operating));

    return Map.of("date", date.toString(), "operating", operating, "reason", reason);
  }
}
