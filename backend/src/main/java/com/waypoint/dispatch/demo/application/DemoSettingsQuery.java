package com.waypoint.dispatch.demo.application;

import com.waypoint.dispatch.demo.contract.DemoView;
import com.waypoint.dispatch.demo.domain.DemoSettings;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.time.TimeAdjustment;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DemoSettingsQuery implements TimeAdjustment, com.waypoint.dispatch.demo.contract.DemoRuntime {
  private final Database database;
  public DemoSettingsQuery(Database database) { this.database = database; }
  public Map<String,Object> row() {
    return database.readAs(ModuleRole.DEMO, null, () -> database.queryOne("SELECT * FROM demo.settings WHERE id"));
  }
  public static DemoSettings settings(Map<String,Object> r) {
    return new DemoSettings((Boolean)r.get("enabled"), ((Number)r.get("clock_offset_seconds")).longValue(),
        ((Number)r.get("sim_point_interval_ms")).intValue(), ((Number)r.get("position_flush_ms")).intValue(),
        (Boolean)r.get("banner"), ((Number)r.get("speed")).intValue());
  }
  @Override public long offsetSeconds() {
    var s = settings(row());
    return s.enabled() ? s.offsetSeconds() : 0;
  }
  public DemoView view() {
    try {
      var r = row(); var s = settings(r);
      return new DemoView(s.enabled(), Clock.system().now().plusSeconds(s.offsetSeconds()), s.offsetSeconds(),
          s.banner(), s.simPointIntervalMs(), s.positionFlushMs(), s.speed(), ((Number)r.get("row_version")).longValue());
    } catch (RuntimeException unavailable) {
      return new DemoView(false, Clock.system().now(), 0, false, 2000, 60000, 1, 0);
    }
  }

  public java.util.List<Map<String, Object>> runs(java.util.UUID actor,
      java.time.Instant beforeStartedAt, java.util.UUID beforeId) {
    if ((beforeStartedAt == null) != (beforeId == null))
      throw new IllegalArgumentException("Both run cursor fields are required");
    return database.readAs(ModuleRole.DEMO, actor, () -> beforeStartedAt == null
        ? database.query("SELECT id,scenario_key,actor,reason,started_at,outcome,details,row_version"
            + " FROM demo.scenario_runs ORDER BY started_at DESC,id DESC LIMIT 50")
        : database.query("SELECT id,scenario_key,actor,reason,started_at,outcome,details,row_version"
            + " FROM demo.scenario_runs WHERE (started_at,id) < (?,?)"
            + " ORDER BY started_at DESC,id DESC LIMIT 50",
            java.sql.Timestamp.from(beforeStartedAt), beforeId));
  }

  /** Simulated vehicles, newest first, keyset paginated on (started_at, id). */
  public java.util.List<Map<String, Object>> simulations(java.util.UUID actor,
      java.time.Instant beforeStartedAt, java.util.UUID beforeId) {
    if ((beforeStartedAt == null) != (beforeId == null))
      throw new IllegalArgumentException("Both simulation cursor fields are required");
    String columns = "SELECT id,vehicle_id,service_date,trip_id,tick,jsonb_array_length(waypoints) AS waypoints,status,failure,started_at FROM demo.simulations";
    return database.readAs(ModuleRole.DEMO, actor, () -> beforeStartedAt == null
        ? database.query(columns + " ORDER BY started_at DESC,id DESC LIMIT 50")
        : database.query(columns + " WHERE (started_at,id) < (?,?) ORDER BY started_at DESC,id DESC LIMIT 50",
            java.sql.Timestamp.from(beforeStartedAt), beforeId));
  }
}
