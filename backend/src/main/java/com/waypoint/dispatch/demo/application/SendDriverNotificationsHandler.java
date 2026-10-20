package com.waypoint.dispatch.demo.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.demo.contract.DemoEvents.DriverNotificationsRequested;
import com.waypoint.dispatch.identity.contract.DemoAccounts;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Puts a few sample notifications in the demo driver's inbox, so judges can see
 * the driver's notifications while planning or loading is still under way. Admin
 * only and refused while demo mode is off. It publishes one event that
 * Notification turns into the samples; no plan, trip or order is touched. The
 * driver is the demo account Identity names (the first of
 * {@code DEMO_SIGN_IN_ACCOUNTS} holding the role).
 */
@Component
public class SendDriverNotificationsHandler implements CommandHandler {
  static final String KIND = "demo:SendDriverNotifications";

  private final Database db;
  private final DemoAccess access;
  private final DemoSettingsQuery settings;
  private final DemoAccounts accounts;
  private final EventPublisher events;
  private final ObjectMapper json;
  private final Clock clock;

  public SendDriverNotificationsHandler(Database db, DemoAccess access, DemoSettingsQuery settings,
      DemoAccounts accounts, EventPublisher events, ObjectMapper json, Clock clock) {
    this.db = db;
    this.access = access;
    this.settings = settings;
    this.accounts = accounts;
    this.events = events;
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
    SimulationHandlers.requireEnabled(settings);
    String reason = SimulationHandlers.reason(command);
    UUID driver = accounts.accounts().get("driver");
    if (driver == null)
      throw new DomainException(ErrorCode.CONFLICT, "There is no demo driver account to notify");
    Instant now = clock.now();
    LocalDate day = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    events.publish(actor, new DriverNotificationsRequested(command.commandId(), driver, day, now));
    db.update("INSERT INTO demo.scenario_runs(id,scenario_key,actor,reason,outcome,details) VALUES (?,?,?,?,?,?::jsonb)",
        command.commandId(), kind(), actor.userId(), reason, "completed",
        json.valueToTree(Map.of("driverUserId", driver.toString(), "serviceDate", day.toString())).toString());
    return Map.of("driverUserId", driver.toString(), "status", "sent");
  }
}
