package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.ordering.contract.DemoDayQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Records preparation intent. The coordinator dispatches owner commands after commit. */
@Component
public class ResetDayHandler implements CommandHandler {
  private final Database db;
  private final DemoAccess access;
  private final DemoDayQuery days;
  private final ReferenceQuery reference;
  private final ObjectMapper json;
  private final Clock clock;

  public ResetDayHandler(Database db, DemoAccess access, DemoDayQuery days,
      ReferenceQuery reference, ObjectMapper json, Clock clock) {
    this.db = db;
    this.access = access;
    this.days = days;
    this.reference = reference;
    this.json = json;
    this.clock = clock;
  }

  @Override public String kind() { return "demo:ResetDay"; }
  @Override public String action() { return kind(); }
  @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }
  @Override public String resource(Command command) { return "wpt:demo:settings:global"; }

  @Override
  public Object handle(Actor actor, Command command) {
    access.admin(actor);
    db.queryOne("SELECT pg_advisory_xact_lock(hashtext('demo.reset')) AS locked");
    Map<String, Object> settings = db.queryOne("SELECT enabled, row_version FROM demo.settings WHERE id");
    if (!Boolean.TRUE.equals(settings.get("enabled")))
      throw new DomainException(ErrorCode.CONFLICT, "Demo mode is off");
    if (command.expectedVersion() == null
        || command.expectedVersion() != ((Number) settings.get("row_version")).longValue())
      throw new DomainException(ErrorCode.VERSION_CONFLICT, "Refresh demo settings first");
    String reason = CommandPayload.of(command).requiredText("reason");
    if (reason.length() < 3 || reason.length() > 500)
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Use a reason of 3-500 characters");
    if (db.queryOne("SELECT 1 FROM demo.scenario_runs WHERE scenario_key = 'demo:ResetDay' AND outcome = 'preparing' LIMIT 1") != null)
      throw new DomainException(ErrorCode.CONFLICT, "A demo day is already being prepared");
    LocalDate date = CommandPayload.of(command).optionalDate("serviceDate");
    if (date == null) {
      date = reference.nextOperatingDay(clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate().plusDays(1));
    }
    if (date.isAfter(clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate().plusDays(7))
        || !reference.isOperating(date)
        || !days.empty("Peliyagoda", date)) {
      throw new DomainException(ErrorCode.CONFLICT, "Choose an empty operating date within seven days");
    }
    Map<String, Object> detail = Map.of("serviceDate", date.toString(), "step", 0);
    db.update("INSERT INTO demo.scenario_runs(id,scenario_key,actor,reason,outcome,details) VALUES (?,?,?,?,?,?::jsonb)",
        command.commandId(), kind(), actor.userId(), reason, "preparing", json.valueToTree(detail).toString());
    return Map.of("runId", command.commandId(), "serviceDate", date.toString(), "status", "preparing");
  }
}
