package com.waypoint.dispatch.sync.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.contract.SyncCommands;
import com.waypoint.dispatch.sync.contract.SyncQuery;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import com.waypoint.dispatch.platform.messaging.CommandAuthorizer;
import com.waypoint.dispatch.sync.infrastructure.OperationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads of {@code sync.operations}. Every read runs as the actor, so row-level security returns
 * only their own operations whatever the caller asks for.
 */
@Component
public class OperationsQuery implements SyncQuery {
  public static final int PAGE = 100;

  private final OperationRepository operations;
  private final Database database;
  private final Optional<CommandAuthorizer> authorizer;

  public OperationsQuery(
      OperationRepository operations, Database database, Optional<CommandAuthorizer> authorizer) {
    this.operations = operations;
    this.database = database;
    this.authorizer = authorizer;
  }

  /** A keyset cursor: the last row's received time and id. */
  public record Cursor(Instant receivedAt, UUID operationId) {
    public static final Cursor START = new Cursor(Instant.EPOCH, new UUID(0, 0));

    public static Cursor parse(String value) {
      if (value == null || value.isBlank()) {
        return START;
      }
      int at = value.lastIndexOf('_');
      try {
        return new Cursor(
            Instant.parse(value.substring(0, at)), UUID.fromString(value.substring(at + 1)));
      } catch (RuntimeException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "since is not a cursor: " + value);
      }
    }

    @Override
    public String toString() {
      return receivedAt + "_" + operationId;
    }
  }

  public record Page(List<OperationView> operations, String nextCursor) {}

  public Page since(Actor actor, Cursor cursor) {
    String denial =
        authorizer.isPresent()
            ? authorizer.get().denyReason(actor, SyncCommands.READ, null, null).orElse(null)
            : "Authorization is not configured";
    if (denial != null) {
      throw new DomainException(ErrorCode.FORBIDDEN, denial);
    }
    List<OperationView> rows =
        database.asModule(
            ModuleRole.SYNC,
            actor.userId(),
            () -> operations.after(cursor.receivedAt(), cursor.operationId(), PAGE));
    String next =
        rows.size() < PAGE
            ? null
            : new Cursor(rows.get(rows.size() - 1).receivedAt(), rows.get(rows.size() - 1).operationId())
                .toString();
    return new Page(rows, next);
  }

  @Override
  public List<OperationView> pendingFor(UUID userId, UUID deviceId) {
    return database.asModule(ModuleRole.SYNC, userId, () -> operations.pendingFor(deviceId));
  }

  @Override
  public List<OperationView> conflictsFor(UUID userId) {
    return database.asModule(ModuleRole.SYNC, userId, () -> operations.conflictsFor(userId));
  }
}
