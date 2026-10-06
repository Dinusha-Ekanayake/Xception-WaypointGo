package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.FuelPassRules;
import com.waypoint.dispatch.execution.infrastructure.JdbcFuelPasses;
import com.waypoint.dispatch.execution.infrastructure.JdbcFuelPasses.Pass;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An administrator withdraws a vehicle's Fuel Pass, for example when the QR was
 * lost or reissued (FF-06b). The pass is revoked with an actor, a reason and a
 * time, never deleted; the driver's phone is refused on its next fetch and drops
 * the image it holds.
 */
@Component
public class RevokeFuelPassHandler implements CommandHandler {
  private final JdbcFuelPasses passes;
  private final Metrics metrics;
  private final Clock clock;

  public RevokeFuelPassHandler(JdbcFuelPasses passes, Metrics metrics, Clock clock) {
    this.passes = passes;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return ExecutionCommands.REVOKE_FUEL_PASS;
  }

  @Override
  public String action() {
    return ExecutionCommands.REVOKE_FUEL_PASS;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String resource(Command command) {
    return ExecutionMessages.vehicleResource(command);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String vehicleId = payload.requiredText("vehicleId");
    UUID passId = payload.uuid("passId");
    String reason = FuelPassRules.requireReason(payload.text("reason"));
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required: send the pass's row version");
    }
    Pass pass =
        passes.byId(passId)
            .filter(p -> p.vehicleId().equals(vehicleId))
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No Fuel Pass " + passId + " for " + vehicleId));
    if (pass.revoked()) {
      throw new DomainException(ErrorCode.CONFLICT, "This Fuel Pass is already withdrawn");
    }
    if (passes.revoke(passId, command.expectedVersion(), actor.userId(), clock.now(), reason) == 0) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, "The Fuel Pass of " + vehicleId + " changed since this screen loaded");
    }
    metrics.increment("waypoint.execution.fuel_pass", "outcome", "revoked");
    return Map.of("vehicleId", vehicleId, "passId", passId.toString(), "revoked", true);
  }
}
