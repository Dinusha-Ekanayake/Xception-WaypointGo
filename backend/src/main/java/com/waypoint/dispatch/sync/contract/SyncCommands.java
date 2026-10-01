package com.waypoint.dispatch.sync.contract;

import com.waypoint.dispatch.platform.messaging.Command;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of Sync's commands.
 *
 * <p>A submitted operation is an ordinary {@link Command} and is dispatched
 * through the existing command bus, so it gets the same receipt, authorization
 * and version guard as an online write. There is no second write path.
 */
public final class SyncCommands {
  private SyncCommands() {}

  public static final String SUBMIT = "sync:Submit";
  public static final String ACKNOWLEDGE = "sync:Acknowledge";
  public static final String DISCARD = "sync:Discard";
  public static final String RESOLVE = "sync:Resolve";
  public static final String READ = "sync:Read";

  public record SubmittedOperation(long sequence, Command command) {}

  /** The body of {@code POST /api/sync}. */
  public record SubmitBatch(UUID deviceId, List<SubmittedOperation> operations) {

    public SubmitBatch {
      operations = List.copyOf(operations);
    }
  }

  public record AcknowledgeOperation(UUID operationId) {}

  public record DiscardOperation(UUID operationId, String reason) {}

  /** Reapply against the current version the reviewer has seen. */
  public record ResolveOperation(UUID operationId, long expectedVersion) {}
}
