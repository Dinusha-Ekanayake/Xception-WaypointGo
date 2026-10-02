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
 * {@code sync:Resolve}: the owner redid a held conflict on the current version.
 *
 * <p>The redo is not run here. One command is one transaction (architecture rule 3), so the device
 * queues the same command on the version it now sees, under a new id, ahead of this one; it is
 * applied or held like any other write. This settles the held operation as {@code RESOLVED} and
 * records which operation replaced it, so the trail survives (rule 8). Nothing is merged.
 *
 * <p>Row-level security limits both operations to the actor's own (decision D-O).
 */
@Component
public class ResolveOperationHandler implements CommandHandler {
  private final OperationRepository operations;

  public ResolveOperationHandler(OperationRepository operations) {
    this.operations = operations;
  }

  @Override
  public String kind() {
    return SyncCommands.RESOLVE;
  }

  @Override
  public String action() {
    return SyncCommands.RESOLVE;
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
    UUID replacedBy = payload.uuid("replacedBy");
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required");
    }
    if (id.equals(replacedBy)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "An operation cannot replace itself");
    }
    OperationView held =
        operations
            .find(id)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such operation"));
    if (!OperationOutcome.canResolve(held.status())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Only a write held as a conflict can be redone");
    }
    OperationView redo =
        operations
            .find(replacedBy)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.VALIDATION_FAILED, "The redo must be sent before it is named"));
    if (!redo.kind().equals(held.kind())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "The redo must be the same kind of write as the one held");
    }
    operations.resolve(id, command.expectedVersion(), replacedBy);
    return Map.of(
        "operationId", id.toString(), "status", "RESOLVED", "replacedBy", replacedBy.toString());
  }
}
