package com.waypoint.dispatch.identity.domain.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When a run of failed sign-ins becomes a lockout, and for how long (SEC-05).
 *
 * <p>There is no separate lock duration. A counter trips when it holds
 * {@code threshold} failures inside the window, and clears as the oldest of them
 * leaves it, so the wait is the time until that happens. A fixed penalty on top
 * would let anyone extend a stranger's lockout by continuing to fail.
 */
public final class LockoutPolicy {
  private LockoutPolicy() {}

  /**
   * @param recentFailures when the failures still counted happened, in any order. The caller
   *     need not pass more than {@code threshold} of the most recent
   * @return how long to wait, or empty when the counter has not tripped
   */
  public static Optional<Duration> retryAfter(
      List<Instant> recentFailures, int threshold, Duration window, Instant now) {
    List<Instant> counted =
        recentFailures.stream()
            .filter(at -> at.plus(window).isAfter(now))
            .sorted(java.util.Comparator.reverseOrder())
            .toList();
    if (counted.size() < threshold) {
      return Optional.empty();
    }
    // The threshold-th most recent failure is the one whose expiry reopens the door.
    Duration wait = Duration.between(now, counted.get(threshold - 1).plus(window));
    return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
  }
}
