package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.domain.RouteWalker.Waypoint;
import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.GeoPoint;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Start, pause, resume and stop simulated vehicles (issue #231). Admin only and
 * refused while demo mode is off. Starting reads each vehicle's released run
 * sheet and driver through the owning modules' contracts and stores the route;
 * {@link SimulationJob} then drives it through the real command bus.
 */
@Configuration
public class SimulationHandlers {
  @Bean CommandHandler demoStartSimulation(Database db, DemoAccess access, DemoSettingsQuery settings,
      ReferenceQuery reference, ExecutionQuery execution, IdentityQuery identity, ObjectMapper json, Clock clock) {
    return new Start(db, access, settings, reference, execution, identity, json, clock);
  }

  @Bean CommandHandler demoControlSimulations(Database db, DemoAccess access, DemoSettingsQuery settings) {
    return new Control(db, access, settings);
  }

  static void requireEnabled(DemoSettingsQuery settings) {
    if (!settings.view().enabled()) throw new DomainException(ErrorCode.CONFLICT, "Demo mode is off");
  }

  static String reason(Command command) {
    String reason = CommandPayload.of(command).requiredText("reason");
    if (reason.length() < 3 || reason.length() > 500)
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Use a reason of 3-500 characters");
    return reason;
  }

  static final class Start implements CommandHandler {
    private final Database db;
    private final DemoAccess access;
    private final DemoSettingsQuery settings;
    private final ReferenceQuery reference;
    private final ExecutionQuery execution;
    private final IdentityQuery identity;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    Start(Database db, DemoAccess access, DemoSettingsQuery settings, ReferenceQuery reference,
        ExecutionQuery execution, IdentityQuery identity, ObjectMapper json, Clock clock) {
      this.db = db; this.access = access; this.settings = settings; this.reference = reference;
      this.execution = execution; this.identity = identity; this.json = json; this.clock = clock;
    }

    @Override public String kind() { return "demo:StartSimulation"; }
    @Override public String action() { return kind(); }
    @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }
    @Override public String resource(Command c) { return "wpt:demo:settings:global"; }

    @Override
    public Object handle(Actor actor, Command command) {
      access.admin(actor);
      requireEnabled(settings);
      String reason = reason(command);
      CommandPayload p = CommandPayload.of(command);
      String only = p.text("vehicleId");
      LocalDate date = p.optionalDate("serviceDate");
      if (date == null) date = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
      UUID version = reference.currentVersionId()
          .orElseThrow(() -> new DomainException(ErrorCode.CONFLICT, "No published reference data"));

      List<VehicleView> vehicles = new ArrayList<>();
      if (only != null) {
        vehicles.add(reference.vehicle(only, version)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such vehicle")));
      } else {
        for (String depot : reference.depotCodes()) vehicles.addAll(reference.vehiclesOfDepot(depot, version));
      }

      List<String> started = new ArrayList<>();
      Map<String, String> skipped = new LinkedHashMap<>();
      for (VehicleView vehicle : vehicles) {
        String why = start(actor, vehicle, date, version, reason, started);
        if (why != null && only != null) throw new DomainException(ErrorCode.CONFLICT, why);
        if (why != null && !why.equals("no released trip")) skipped.put(vehicle.vehicleId(), why);
      }
      if (started.isEmpty() && only == null)
        throw new DomainException(ErrorCode.CONFLICT, "No released trip on " + date + " can be simulated");
      return Map.of("serviceDate", date.toString(), "started", started, "skipped", skipped);
    }

    private String start(Actor actor, VehicleView vehicle, LocalDate date, UUID version, String reason, List<String> started) {
      Optional<RunSheetView> sheet = execution.runSheet(vehicle.vehicleId(), date);
      List<RunSheetStopView> open = sheet.map(RunSheetView::stops).orElse(List.of()).stream()
          .filter(s -> s.completedAt().isEmpty()).toList();
      if (open.isEmpty()) return "no released trip";
      Optional<UUID> driver = identity.driverOn(vehicle.vehicleId(), date);
      if (driver.isEmpty()) return "no driver assigned";
      List<Waypoint> waypoints = new ArrayList<>();
      reference.depot(vehicle.depotCode(), version).flatMap(d -> d.location()).map(SimulationHandlers::waypoint)
          .ifPresent(waypoints::add);
      for (RunSheetStopView stop : open) {
        reference.outlet(stop.outletId(), version).flatMap(o -> o.location()).map(SimulationHandlers::waypoint)
            .ifPresent(waypoints::add);
      }
      if (waypoints.size() < 2) return "stops have no location";
      boolean active = db.queryOne("SELECT 1 FROM demo.simulations WHERE vehicle_id=? AND service_date=? AND status IN ('running','paused')",
          vehicle.vehicleId(), date) != null;
      if (active) return "already simulated";
      db.update("INSERT INTO demo.simulations(id,vehicle_id,service_date,trip_id,driver_user_id,waypoints,status,reason,started_by)"
              + " VALUES (?,?,?,?,?,?::jsonb,'running',?,?)",
          UuidV7.generate(clock.now(), random), vehicle.vehicleId(), date, open.get(0).tripId(), driver.get(),
          json.valueToTree(waypoints).toString(), reason, actor.userId());
      started.add(vehicle.vehicleId());
      return null;
    }
  }

  static Waypoint waypoint(GeoPoint point) {
    return new Waypoint(point.latitude().doubleValue(), point.longitude().doubleValue());
  }

  static final class Control implements CommandHandler {
    private final Database db;
    private final DemoAccess access;
    private final DemoSettingsQuery settings;

    Control(Database db, DemoAccess access, DemoSettingsQuery settings) {
      this.db = db; this.access = access; this.settings = settings;
    }

    @Override public String kind() { return "demo:ControlSimulations"; }
    @Override public String action() { return kind(); }
    @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }
    @Override public String resource(Command c) { return "wpt:demo:settings:global"; }

    @Override
    public Object handle(Actor actor, Command command) {
      access.admin(actor);
      requireEnabled(settings);
      reason(command);
      CommandPayload p = CommandPayload.of(command);
      String action = p.requiredText("action");
      UUID only = p.optionalUuid("simulationId");
      String from;
      String to;
      switch (action) {
        case "pause" -> { from = "'running'"; to = "paused"; }
        case "resume" -> { from = "'paused'"; to = "running"; }
        case "stop" -> { from = "'running','paused'"; to = "stopped"; }
        default -> throw new DomainException(ErrorCode.VALIDATION_FAILED, "Choose pause, resume or stop");
      }
      int changed = only == null
          ? db.update("UPDATE demo.simulations SET status=?,row_version=row_version+1 WHERE status IN (" + from + ")", to)
          : db.update("UPDATE demo.simulations SET status=?,row_version=row_version+1 WHERE id=? AND status IN (" + from + ")", to, only);
      return Map.of("action", action, "changed", changed);
    }
  }
}
