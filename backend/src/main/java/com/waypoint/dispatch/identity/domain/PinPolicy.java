package com.waypoint.dispatch.identity.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The operator PIN on a shared dock device (Figma 00b, E1, E2).
 *
 * <p>A PIN is a second factor on a device a supervisor has already signed in,
 * never a way in on its own, so four digits are enough. Five wrong tries pause
 * PIN entry for that person for five minutes (R-IAM-26). The count is per
 * person and shared across replicas, because the attempts live in the database.
 */
public final class PinPolicy {
  public static final int MAX_FAILURES = 5;
  public static final Duration PAUSE = Duration.ofMinutes(5);

  private PinPolicy() {}

  public static void requireWellFormed(String pin) {
    if (pin == null || !pin.matches("\\d{4}")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A PIN is four digits", List.of("R-IAM-26"));
    }
  }

  /**
   * Failures since the last success, oldest first, decide whether PIN entry is
   * paused and for how long.
   *
   * @param failures times of failed attempts since the last success, within the pause window
   * @return how long entry stays paused, empty when the person may try now
   */
  public static Optional<Duration> pausedFor(List<Instant> failures, Instant now) {
    if (failures.size() < MAX_FAILURES) {
      return Optional.empty();
    }
    Instant until = failures.get(failures.size() - 1).plus(PAUSE);
    return now.isBefore(until) ? Optional.of(Duration.between(now, until)) : Optional.empty();
  }

  /** Tries left after these failures, never below zero. */
  public static int triesLeft(int failures) {
    return Math.max(0, MAX_FAILURES - failures);
  }
}
