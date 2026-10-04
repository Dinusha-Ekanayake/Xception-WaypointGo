package com.waypoint.dispatch.sync.domain;

import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationStatus;
import java.util.Optional;

/**
 * What a failed offline operation becomes.
 *
 * <p>Three answers, and the difference between them is the whole protocol:
 *
 * <ul>
 *   <li>{@code CONFLICT}: the record moved on while the device was offline. Held for a person to
 *       look at against the current record, never merged (rule 6).
 *   <li>{@code REJECTED}: the rules refused it. Sending it again cannot help.
 *   <li>empty: the server could not decide right now. The operation stays {@code RECEIVED} and the
 *       batch stops, so later operations never overtake it.
 * </ul>
 */
public final class OperationOutcome {
  private OperationOutcome() {}

  /** Empty means "try again later", so the caller stops the batch here. */
  public static Optional<OperationStatus> forFailure(ErrorCode code) {
    return switch (code) {
      case VERSION_CONFLICT -> Optional.of(OperationStatus.CONFLICT);
      case BAD_REQUEST,
              VALIDATION_FAILED,
              NOT_FOUND,
              CONFLICT,
              FORBIDDEN,
              CONSTRAINT_VIOLATED,
              PAYLOAD_TOO_LARGE ->
          Optional.of(OperationStatus.REJECTED);
      // An outage, an expired session, a timeout or backpressure are not the
      // operation's fault, and the same operation can succeed later.
      case DEPENDENCY_UNAVAILABLE, UNAUTHENTICATED, REQUEST_TIMEOUT, RATE_LIMITED ->
          Optional.empty();
    };
  }

  /** Final: the device can forget it once told. RECEIVED is the only state still in flight. */
  public static boolean isSettled(OperationStatus status) {
    return status != OperationStatus.RECEIVED;
  }

  /**
   * Acknowledging tells the server the device has dropped its copy. Only a settled operation can
   * be dropped; acknowledging one still in flight would lose it.
   */
  public static boolean canAcknowledge(OperationStatus status) {
    return isSettled(status);
  }

  /** Only a write the server held for a person can be dropped by one: a conflict or a refusal. */
  public static boolean canDiscard(OperationStatus status) {
    return status == OperationStatus.CONFLICT || status == OperationStatus.REJECTED;
  }

  /**
   * Only a conflict can be redone on the current version. A refusal broke a rule, and the same
   * command on a newer version would break it again.
   */
  public static boolean canResolve(OperationStatus status) {
    return status == OperationStatus.CONFLICT;
  }
}
