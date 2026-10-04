package com.waypoint.dispatch.sync.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a device and a reviewer see of offline operations.
 *
 * <p>A conflict is surfaced, never merged: the operation keeps both the version
 * it was based on and the version it met (architecture rule 6). Operations are
 * never deleted; a discarded one keeps its record and its reason.
 */
public final class SyncViews {
  private SyncViews() {}

  public enum OperationStatus {
    RECEIVED,
    APPLIED,
    CONFLICT,
    REJECTED,
    DISCARDED,
    /** Redone on the current version by its owner; the redo is another operation. */
    RESOLVED
  }

  /**
   * @param operationId the command id, so a replay is recognised
   * @param sequence per device, so operations apply in the order they were recorded
   * @param problemCode the problem type code when rejected or in conflict
   * @param rowVersion the operation's own version, which a discard or resolve names
   */
  public record OperationView(
      UUID operationId,
      UUID deviceId,
      long sequence,
      String kind,
      OperationStatus status,
      Optional<String> problemCode,
      Optional<Long> baseRowVersion,
      Optional<Long> currentRowVersion,
      Instant receivedAt,
      Optional<Instant> appliedAt,
      long rowVersion) {}
}
