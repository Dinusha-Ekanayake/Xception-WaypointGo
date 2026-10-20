package com.waypoint.dispatch.demo.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks for the demo database to start again from a fresh install (R-DEMO-06).
 * This only records the request: the application never holds the power to drop
 * a database. {@code deploy/vps/demo-reset.sh}, run by a timer on the server as
 * the deployment user, recreates {@value DemoCommandHandlers#DEMO_DATABASE} and
 * nothing else; the real database is never touched. Refused unless the
 * deployment opted in with {@code DEMO_RESET=true} and the demo is running in
 * its own database, for anyone but an administrator, without the typed
 * confirmation, or while a reset is already waiting.
 */
@Component
public class RequestResetHandler implements CommandHandler {
  static final String KIND = "demo:RequestReset";

  private final Database db;
  private final DemoAccess access;
  private final boolean separate;

  public RequestResetHandler(Database db, DemoAccess access, @Value("${app.demo.reset:false}") boolean separate) {
    this.db = db;
    this.access = access;
    this.separate = separate;
  }

  @Override public String kind() { return KIND; }
  @Override public String action() { return kind(); }
  @Override public ModuleRole moduleRole() { return ModuleRole.DEMO; }
  @Override public String resource(Command command) { return "wpt:demo:settings:global"; }

  @Override
  public Object handle(Actor actor, Command command) {
    access.admin(actor);
    if (!separate)
      throw new DomainException(ErrorCode.CONFLICT, "Reset is not set up on this deployment");
    if (!DemoCommandHandlers.inDemoDatabase(db))
      throw new DomainException(ErrorCode.CONFLICT, "Turn demo mode on first; only the demo database is ever reset");
    String reason = SimulationHandlers.reason(command);
    if (!"RESET".equals(CommandPayload.of(command).text("confirm")))
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Type RESET to confirm");
    db.queryOne("SELECT pg_advisory_xact_lock(hashtext('demo.reset')) AS locked");
    if (db.queryOne("SELECT 1 FROM demo.scenario_runs WHERE scenario_key = ? AND outcome = 'requested' LIMIT 1", KIND) != null)
      throw new DomainException(ErrorCode.CONFLICT, "A reset is already on its way");
    DemoCommandHandlers.request(db, actor, command.commandId(), KIND, reason);
    return Map.of("runId", command.commandId(), "status", "requested");
  }
}
