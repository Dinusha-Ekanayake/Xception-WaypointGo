package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.ordering.contract.DemoDayQuery;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Records the intent to plan and publish a seeded demo day, separate from
 * seeding its orders, so the loaders' dock board has trips and the drivers hear
 * the plan. The day is the one given, else the latest prepared demo day still
 * ahead; it must hold orders and have no published plan. {@link ResetDayJob}
 * then gives the demo accounts the day and sends {@code plan:PrepareDemoDay}.
 */
@Component
public class PublishDayHandler implements CommandHandler {
  static final String KIND = "demo:PublishDay";
  static final String DEPOT = "Peliyagoda";

  private final Database db;
  private final DemoAccess access;
  private final DemoDayQuery days;
  private final PlanQuery plans;
  private final ObjectMapper json;
  private final Clock clock;

  public PublishDayHandler(Database db, DemoAccess access, DemoDayQuery days, PlanQuery plans,
      ObjectMapper json, Clock clock) {
    this.db = db;
    this.access = access;
    this.days = days;
    this.plans = plans;
    this.json = json;
    this.clock = clock;
  }

  @Override public String kind() { return KIND; }
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
    if (db.queryOne("SELECT 1 FROM demo.scenario_runs WHERE scenario_key IN ('demo:ResetDay','demo:PublishDay') AND outcome = 'preparing' LIMIT 1") != null)
      throw new DomainException(ErrorCode.CONFLICT, "A demo day is already being prepared");
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    LocalDate date = CommandPayload.of(command).optionalDate("serviceDate");
    if (date == null) {
      Map<String, Object> last = db.queryOne(
          "SELECT details->>'serviceDate' AS day FROM demo.scenario_runs WHERE scenario_key = 'demo:ResetDay'"
              + " AND outcome = 'completed' AND (details->>'serviceDate')::date >= ? ORDER BY started_at DESC, id DESC LIMIT 1",
          java.sql.Date.valueOf(today));
      if (last == null)
        throw new DomainException(ErrorCode.CONFLICT, "Prepare a demo day first; there is no prepared day ahead to publish");
      date = LocalDate.parse((String) last.get("day"));
    }
    if (date.isBefore(today) || days.empty(DEPOT, date))
      throw new DomainException(ErrorCode.CONFLICT, "Choose a prepared day ahead with orders at " + DEPOT);
    if (plans.publishedPlan(DEPOT, date).isPresent())
      throw new DomainException(ErrorCode.CONFLICT, "The plan for " + date + " is already published");
    Map<String, Object> detail = Map.of("serviceDate", date.toString(), "step", 0);
    db.update("INSERT INTO demo.scenario_runs(id,scenario_key,actor,reason,outcome,details) VALUES (?,?,?,?,?,?::jsonb)",
        command.commandId(), kind(), actor.userId(), reason, "preparing", json.valueToTree(detail).toString());
    return Map.of("runId", command.commandId(), "serviceDate", date.toString(), "status", "preparing");
  }
}
