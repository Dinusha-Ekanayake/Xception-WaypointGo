package com.waypoint.dispatch.identity.domain.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SEC-05: when a run of failures becomes a lockout, and how long it lasts. */
class LockoutPolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(15);

  @Test
  void fewerFailuresThanTheThresholdIsNotALockout() {
    assertTrue(LockoutPolicy.retryAfter(failuresAgo(1, 2, 3), 4, WINDOW, NOW).isEmpty());
  }

  @Test
  void theWaitIsUntilTheOldestCountedFailureLeavesTheWindow() {
    // Eight failures, the oldest of them eleven minutes ago: four minutes to go.
    Duration wait =
        LockoutPolicy.retryAfter(failuresAgo(1, 2, 3, 4, 5, 6, 7, 11), 8, WINDOW, NOW).orElseThrow();
    assertEquals(Duration.ofMinutes(4), wait);
  }

  @Test
  void moreFailuresDoNotExtendTheWaitPastTheThresholdThOne() {
    // Ten in the window with a threshold of eight: the door reopens when the
    // eighth most recent expires, not the tenth. Continuing to fail must not be
    // a way to keep a stranger locked out for longer.
    Duration wait =
        LockoutPolicy.retryAfter(failuresAgo(1, 2, 3, 4, 5, 6, 7, 8, 13, 14), 8, WINDOW, NOW)
            .orElseThrow();
    assertEquals(Duration.ofMinutes(7), wait);
  }

  @Test
  void aFailureOutsideTheWindowDoesNotCount() {
    assertTrue(
        LockoutPolicy.retryAfter(failuresAgo(1, 2, 3, 16), 4, WINDOW, NOW).isEmpty(),
        "the fourth failure is sixteen minutes old and the window is fifteen");
  }

  @Test
  void theOrderTheFailuresArriveInDoesNotMatter() {
    assertEquals(
        LockoutPolicy.retryAfter(failuresAgo(11, 1, 5, 3), 4, WINDOW, NOW),
        LockoutPolicy.retryAfter(failuresAgo(1, 3, 5, 11), 4, WINDOW, NOW));
  }

  private static List<Instant> failuresAgo(int... minutes) {
    List<Instant> failures = new ArrayList<>();
    for (int minute : minutes) {
      failures.add(NOW.minus(Duration.ofMinutes(minute)));
    }
    return failures;
  }
}
