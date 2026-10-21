package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.domain.RouteWalker;
import com.waypoint.dispatch.demo.domain.RouteWalker.Waypoint;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.messaging.CommandResult;
import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Moves every running simulated vehicle one point per tick (issue #231). Each
 * point is a delivery:RecordPositions command through the real bus, as the
 * vehicle's assigned driver, stamped with the business (demo) clock, so the
 * store, dispatcher and driver maps all show it. At a stop's first pause point it
 * records the arrival, at its last the delivery of every unit (R-DEMO-04,
 * docs/issues/demo-stops/PLAN.md); never proof or receipt. Every command id is
 * derived from simulation, tick or stop, so a repeated tick is answered by its
 * receipt, never stored twice. Does nothing while demo mode is off.
 */
@Component
public class SimulationJob implements ScheduledJob {
  private static final TypeReference<List<Waypoint>> WAYPOINTS = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> STOPS = new TypeReference<>() {};
  private final Database db;
  private final CommandBus bus;
  private final ObjectMapper json;
  private final DemoSettingsQuery settings;
  private final ExecutionQuery execution;
  private final Clock clock;

  public SimulationJob(Database db, CommandBus bus, ObjectMapper json, DemoSettingsQuery settings,
      ExecutionQuery execution, Clock clock) {
    this.db = db;
    this.bus = bus;
    this.json = json;
    this.settings = settings;
    this.execution = execution;
    this.clock = clock;
  }

  @Override public String name() { return "demo.simulations"; }
  @Override public String cron() { return "*/2 * * * * *"; }
  @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }

  @Override
  public void run(Instant ignored) {
    if (!settings.view().enabled()) return;
    List<Map<String, Object>> running = db.asSystem(ModuleRole.DEMO, () -> db.query(
        "SELECT id,vehicle_id,service_date,trip_id,driver_user_id,waypoints,stops,tick,row_version FROM demo.simulations"
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
    // Real time, as a phone stamps its fixes (DEMO-12).
    point.put("recordedAt", clock.realTime().now().toString());
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
    int recorded;
    try {
      recorded = recordStops(sim, tick);
    } catch (RuntimeException refused) {
      finish(id, version, "failed", "Stop refused: " + refused.getMessage());
      return;
    }
    db.asSystem(ModuleRole.DEMO, () -> db.update(
        "UPDATE demo.simulations SET tick=tick+1,stops_recorded=stops_recorded+?,row_version=row_version+1"
            + " WHERE id=? AND row_version=? AND status='running'",
        recorded, id, version));
  }

  /** The stops this tick arrives at or leaves; how many deliveries it recorded. */
  private int recordStops(Map<String, Object> sim, int tick) {
    if (sim.get("stops") == null) return 0;
    List<Map<String, Object>> stops;
    try {
      stops = json.readValue(sim.get("stops").toString(), STOPS);
    } catch (Exception unreadable) {
      throw new IllegalStateException("saved stops are unreadable");
    }
    int recorded = 0;
    for (Map<String, Object> stop : stops) {
      boolean arrive = ((Number) stop.get("arrive")).intValue() == tick;
      boolean done = ((Number) stop.get("done")).intValue() == tick;
      if (!arrive && !done) continue;
      UUID deliveryId = UUID.fromString(stop.get("deliveryId").toString());
      Optional<RunSheetStopView> now = stopOf(sim, deliveryId);
      // Skipped by the dispatcher, or already recorded by a person: passed by.
      if (now.isEmpty() || now.get().completedAt().isPresent()) continue;
      long version = now.get().rowVersion();
      if (now.get().startedAt().isEmpty()) {
        version = send(sim, deliveryId, "start", ExecutionCommands.START_STOP, version, Map.of());
      }
      if (now.get().arrivedAt().isEmpty()) {
        version = send(sim, deliveryId, "arrive", ExecutionCommands.RECORD_ARRIVAL, version, Map.of());
      }
      if (done) {
        // Every unit, no lines and no proof: the store confirms receipt by hand.
        send(sim, deliveryId, "deliver", ExecutionCommands.RECORD, version,
            Map.of("outcome", "DELIVERED", "dispositionNote", "demo vehicle"));
        recorded++;
      }
    }
    return recorded;
  }

  private Optional<RunSheetStopView> stopOf(Map<String, Object> sim, UUID deliveryId) {
    LocalDate day = ((java.sql.Date) sim.get("service_date")).toLocalDate();
    return db.asSystem(ModuleRole.DEMO, () -> execution.runSheet((String) sim.get("vehicle_id"), day)).stream()
        .flatMap(sheet -> sheet.stops().stream())
        .filter(s -> s.deliveryId().equals(deliveryId) && s.outcome() != DeliveryOutcome.SKIPPED)
        .findFirst();
  }

  /** One command as the driver; its id from simulation, stop and step, so a repeat is answered by its receipt. */
  private long send(Map<String, Object> sim, UUID deliveryId, String step, String kind, long expected,
      Map<String, Object> extra) {
    Map<String, Object> payload = new LinkedHashMap<>(extra);
    payload.put("deliveryId", deliveryId.toString());
    UUID commandId = UUID.nameUUIDFromBytes((sim.get("id") + ":" + deliveryId + ":" + step).getBytes(StandardCharsets.UTF_8));
    CommandResult result = bus.dispatch(Actor.user((UUID) sim.get("driver_user_id")),
        new Command(commandId, kind, expected, json.valueToTree(payload), clock.now()),
        "demo:simulation:" + sim.get("id"));
    Object version = result.value() instanceof Map<?, ?> m ? m.get("rowVersion") : null;
    return version instanceof Number n ? n.longValue() : expected + 1;
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
