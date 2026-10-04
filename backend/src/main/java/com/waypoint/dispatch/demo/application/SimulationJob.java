package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.domain.RouteWalker;
import com.waypoint.dispatch.demo.domain.RouteWalker.Waypoint;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Moves every running simulated vehicle one point per tick (issue #231). Each
 * point is a delivery:RecordPositions command through the real bus, as the
 * vehicle's assigned driver, stamped with the business (demo) clock, so the
 * store, dispatcher and driver maps all show it. The command id is derived from
 * simulation and tick, so a repeated tick is answered by its receipt, never
 * stored twice. Does nothing while demo mode is off.
 */
@Component
public class SimulationJob implements ScheduledJob {
  private static final TypeReference<List<Waypoint>> WAYPOINTS = new TypeReference<>() {};
  private final Database db;
  private final CommandBus bus;
  private final ObjectMapper json;
  private final DemoSettingsQuery settings;
  private final Clock clock;

  public SimulationJob(Database db, CommandBus bus, ObjectMapper json, DemoSettingsQuery settings, Clock clock) {
    this.db = db;
    this.bus = bus;
    this.json = json;
    this.settings = settings;
    this.clock = clock;
  }

  @Override public String name() { return "demo.simulations"; }
  @Override public String cron() { return "*/2 * * * * *"; }
  @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }

  @Override
  public void run(Instant ignored) {
    if (!settings.view().enabled()) return;
    List<Map<String, Object>> running = db.asSystem(ModuleRole.DEMO, () -> db.query(
        "SELECT id,vehicle_id,trip_id,driver_user_id,waypoints,tick,row_version FROM demo.simulations"
            + " WHERE status='running' ORDER BY started_at,id LIMIT 60"));
    for (Map<String, Object> sim : running) step(sim);
  }

  private void step(Map<String, Object> sim) {
    UUID id = (UUID) sim.get("id");
    int tick = ((Number) sim.get("tick")).intValue();
    long version = ((Number) sim.get("row_version")).longValue();
    List<Waypoint> path;
    try {
      path = RouteWalker.path(json.readValue(sim.get("waypoints").toString(), WAYPOINTS));
    } catch (Exception unreadable) {
      finish(id, version, "failed", "Saved route is unreadable");
      return;
    }
    if (tick >= path.size()) {
      finish(id, version, "finished", null);
      return;
    }
    Waypoint here = path.get(tick);
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("recordedAt", clock.now().toString());
    point.put("latitude", round(here.latitude()));
    point.put("longitude", round(here.longitude()));
    point.put("accuracyM", 5);
    Double heading = tick + 1 < path.size() ? RouteWalker.heading(here, path.get(tick + 1)) : null;
    if (heading != null) point.put("headingDeg", heading);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("vehicleId", sim.get("vehicle_id"));
    if (sim.get("trip_id") != null) payload.put("tripId", sim.get("trip_id").toString());
    payload.put("points", List.of(point));
    UUID commandId = UUID.nameUUIDFromBytes((id + ":" + tick).getBytes(StandardCharsets.UTF_8));
    try {
      bus.dispatch(Actor.user((UUID) sim.get("driver_user_id")),
          new Command(commandId, "delivery:RecordPositions", null, json.valueToTree(payload), clock.now()),
          "demo:simulation:" + id);
    } catch (RuntimeException refused) {
      finish(id, version, "failed", "Position refused: " + refused.getMessage());
      return;
    }
    db.asSystem(ModuleRole.DEMO, () -> db.update(
        "UPDATE demo.simulations SET tick=tick+1,row_version=row_version+1 WHERE id=? AND row_version=? AND status='running'",
        id, version));
  }

  private void finish(UUID id, long version, String status, String failure) {
    db.asSystem(ModuleRole.DEMO, () -> db.update(
        "UPDATE demo.simulations SET status=?,failure=?,row_version=row_version+1 WHERE id=? AND row_version=?",
        status, failure, id, version));
  }

  private static BigDecimal round(double value) {
    return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
  }
}
