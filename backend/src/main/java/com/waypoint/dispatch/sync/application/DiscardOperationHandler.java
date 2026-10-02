package com.waypoint.dispatch.sync.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.contract.SyncCommands;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import com.waypoint.dispatch.sync.domain.OperationOutcome;
import com.waypoint.dispatch.sync.infrastructure.OperationRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code sync:Discard}: the owner drops a write the server held, with a reason (rule 8).
 *
 * <p>Runs inside the bus transaction as {@code waypoint_sync}, so row-level security limits it to
 * the actor's own operations: decision D-O, nobody reviews another person's write. Another
 * account's id reads as not found.
 */
@Component
public class DiscardOperationHandler implements CommandHandler {
  private final OperationRepository operations;

  public DiscardOperationHandler(OperationRepository operations) {
    this.operations = operations;
  }

  @Override
  public String kind() {
    return SyncCommands.DISCARD;
  }

  @Override
  public String action() {
    return SyncCommands.DISCARD;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.SYNC;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("operationId");
    return id == null ? null : "wpt:sync:operation:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID id = payload.uuid("operationId");
    String reason = payload.requiredText("reason");
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
    }
    OperationView operation =
        operations
            .find(id)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such operation"));
    if (!OperationOutcome.canDiscard(operation.status())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Only a write the server held can be discarded");
    }
    operations.discard(id, command.expectedVersion(), reason);
    return Map.of("operationId", id.toString(), "status", "DISCARDED");
  }
}
