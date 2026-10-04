package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A persisted workflow; every owning command has its own audited transaction. */
@Component
public class ResetDayJob implements ScheduledJob {
  private static final List<String> STEPS = List.of(
      "reference:PrepareDemoDay", "iam:PrepareDemoDay", "order:PrepareDemoDay");
  private final Database db;
  private final CommandBus bus;
  private final ObjectMapper json;
  private final DemoSettingsQuery settings;

  public ResetDayJob(Database db, CommandBus bus, ObjectMapper json, DemoSettingsQuery settings) {
    this.db = db;
    this.bus = bus;
    this.json = json;
    this.settings = settings;
  }

  @Override public String name() { return "demo.reset-day"; }
  @Override public String cron() { return "*/5 * * * * *"; }
  @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }

  @Override
  public void run(Instant ignored) {
    if (!settings.view().enabled()) return;
    List<Map<String, Object>> runs = db.asSystem(ModuleRole.DEMO, () -> db.query(
        "SELECT id,actor,reason,details,row_version FROM demo.scenario_runs WHERE scenario_key = 'demo:ResetDay' AND outcome = 'preparing' ORDER BY started_at,id LIMIT 1"));
    for (Map<String, Object> run : runs) {
      UUID id = (UUID) run.get("id");
      JsonNode detail;
      try { detail = json.readTree(run.get("details").toString()); }
      catch (Exception invalid) { fail(id, "Saved preparation is unreadable"); continue; }
      int step = detail.path("step").asInt();
      if (step < 0 || step >= STEPS.size()) { fail(id, "Invalid preparation step"); continue; }
      String kind = STEPS.get(step);
      UUID commandId = UUID.nameUUIDFromBytes((id + ":" + kind).getBytes(StandardCharsets.UTF_8));
      String date = detail.path("serviceDate").asText();
      Command command = new Command(commandId, kind, 0L,
          json.valueToTree(Map.of("serviceDate", date, "reason", run.get("reason"))),
          Instant.EPOCH);
      try {
        bus.dispatch(Actor.user((UUID) run.get("actor")), command, "demo:" + id);
        int next = step + 1;
        db.asSystem(ModuleRole.DEMO, () -> db.update(
            "UPDATE demo.scenario_runs SET outcome=?,details=jsonb_set(details - 'failure' - 'attempts','{step}',to_jsonb(?::int)),row_version=row_version+1 WHERE id=? AND row_version=?",
            next == STEPS.size() ? "completed" : "preparing", next, id, run.get("row_version")));
      } catch (RuntimeException failure) {
        int attempts = detail.path("attempts").asInt() + 1;
        String reason = "Step " + kind + " failed: " + failure.getClass().getSimpleName();
        db.asSystem(ModuleRole.DEMO, () -> db.update(
            "UPDATE demo.scenario_runs SET outcome=?,details=jsonb_set(jsonb_set(details,'{failure}',to_jsonb(?::text)),'{attempts}',to_jsonb(?::int)),row_version=row_version+1 WHERE id=? AND row_version=? AND outcome='preparing'",
            attempts >= 3 ? "failed" : "preparing", reason, attempts, id, run.get("row_version")));
      }
    }
  }

  private void fail(UUID id, String reason) {
    db.asSystem(ModuleRole.DEMO, () -> db.update(
        "UPDATE demo.scenario_runs SET outcome='failed',details=jsonb_set(details,'{failure}',to_jsonb(?::text)),row_version=row_version+1 WHERE id=? AND outcome='preparing'",
        reason, id));
  }
}
