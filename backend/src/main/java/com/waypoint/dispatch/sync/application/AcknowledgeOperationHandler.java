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
 * {@code sync:Acknowledge}: the device has dropped its copy of a settled operation.
 *
 * <p>Runs inside the bus transaction as {@code waypoint_sync}, so row-level security already
 * limits it to the actor's own operations: another account's id reads as not found.
 */
@Component
public class AcknowledgeOperationHandler implements CommandHandler {
  private final OperationRepository operations;

  public AcknowledgeOperationHandler(OperationRepository operations) {
    this.operations = operations;
  }

  @Override
  public String kind() {
    return SyncCommands.ACKNOWLEDGE;
  }

  @Override
  public String action() {
    return SyncCommands.ACKNOWLEDGE;
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
    UUID id = CommandPayload.of(command).uuid("operationId");
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
    }
    OperationView operation =
        operations
            .find(id)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such operation"));
    if (!OperationOutcome.canAcknowledge(operation.status())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "An operation still in flight cannot be acknowledged");
    }
    operations.acknowledge(id, command.expectedVersion());
    return Map.of("operationId", id.toString(), "status", operation.status().name());
  }
}
