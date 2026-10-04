package com.waypoint.dispatch.intelligence.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What on a live trip needs the dispatcher (issue #268): one stop's facts in,
 * at most one finding out, the most severe. The one definition of these rules,
 * read by the watch and, through it, by the screen.
 *
 * <p>A failed stop always needs a decision. A stop that will arrive after its
 * window closes is at risk, and critical once the window is about to close. A
 * stop running late but still inside its window is worth knowing. A delivery
 * with no proof is chased once the driver has had time to add it. A skipped
 * stop, or one finished with proof, needs nothing.
 */
public final class AttentionPolicy {
  private AttentionPolicy() {}

  public enum Kind {
    FAILED_STOP,
    WINDOW_AT_RISK,
    RUNNING_LATE,
    PROOF_OWED
  }

  /** Most urgent first. */
  public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM
  }

  public enum Progress {
    /** Not reached yet, or at the stop with the delivery still to record. */
    OPEN,
    DELIVERED,
    FAILED,
    SKIPPED
  }

  /**
   * @param plannedArrival when the plan put the vehicle at the stop
   * @param expectedArrival the planned arrival moved by the delay seen so far; empty before any delay is observed
   * @param windowClose when the outlet stops receiving
   * @param completedAt when the delivery was recorded; empty while the stop is open
   */
  public record StopFacts(
      Progress progress,
      Instant plannedArrival,
      Optional<Instant> expectedArrival,
      Instant windowClose,
      Optional<Instant> completedAt,
      boolean proofCaptured) {}

  /** @param minutesLeft minutes until the window closes, for the order and the screen; empty where no clock applies */
  public record Finding(Kind kind, Severity severity, Optional<Long> minutesLeft) {}

  public static Optional<Finding> assess(StopFacts stop, AttentionThresholds thresholds, Instant now) {
    return switch (stop.progress()) {
      case SKIPPED -> Optional.empty();
      case FAILED -> Optional.of(new Finding(Kind.FAILED_STOP, Severity.CRITICAL, Optional.empty()));
      case DELIVERED -> proofOwed(stop, thresholds, now);
      case OPEN -> late(stop, thresholds, now);
    };
  }

  private static Optional<Finding> proofOwed(StopFacts stop, AttentionThresholds thresholds, Instant now) {
    if (stop.proofCaptured() || stop.completedAt().isEmpty()) {
      return Optional.empty();
    }
    long since = Duration.between(stop.completedAt().get(), now).toMinutes();
    return since >= thresholds.proofGraceMinutes()
        ? Optional.of(new Finding(Kind.PROOF_OWED, Severity.MEDIUM, Optional.empty()))
        : Optional.empty();
  }

  private static Optional<Finding> late(StopFacts stop, AttentionThresholds thresholds, Instant now) {
    Instant arrival = stop.expectedArrival().orElse(stop.plannedArrival());
    long minutesLeft = Duration.between(now, stop.windowClose()).toMinutes();
    if (arrival.isAfter(stop.windowClose())) {
      Severity severity = minutesLeft <= thresholds.windowWarnMinutes() ? Severity.CRITICAL : Severity.HIGH;
      return Optional.of(new Finding(Kind.WINDOW_AT_RISK, severity, Optional.of(minutesLeft)));
    }
    long lateBy = Duration.between(stop.plannedArrival(), arrival).toMinutes();
    return lateBy >= thresholds.lateMinutes()
        ? Optional.of(new Finding(Kind.RUNNING_LATE, Severity.MEDIUM, Optional.of(minutesLeft)))
        : Optional.empty();
  }
}
