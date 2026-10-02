package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {
  private final RetryBackoff backoff =
      new RetryBackoff(8, Duration.ofSeconds(2), Duration.ofMinutes(5));

  @Test
  void theCeilingDoublesFromTheBaseAndStopsAtTheCap() {
    assertEquals(Duration.ofSeconds(2), backoff.ceiling(1));
    assertEquals(Duration.ofSeconds(4), backoff.ceiling(2));
    assertEquals(Duration.ofSeconds(16), backoff.ceiling(4));
    assertEquals(Duration.ofSeconds(128), backoff.ceiling(7));
    assertEquals(Duration.ofSeconds(256), backoff.ceiling(8), "256s is still under the 300s cap");
    assertEquals(Duration.ofMinutes(5), backoff.ceiling(9), "512s is capped");
    assertEquals(Duration.ofMinutes(5), backoff.ceiling(60), "a huge count must not overflow");
  }

  @Test
  void jitterSpreadsTheDelayAcrossZeroToTheCeiling() {
    assertEquals(Duration.ZERO, backoff.delay(3, () -> 0.0));
    assertEquals(Duration.ofSeconds(4), backoff.delay(3, () -> 0.5));
    assertTrue(backoff.delay(3, () -> 0.999999).compareTo(backoff.ceiling(3)) < 0);
  }

  @Test
  void anEventIsExhaustedOnTheLastAllowedFailure() {
    assertFalse(backoff.isExhausted(7));
    assertTrue(backoff.isExhausted(8));
    assertTrue(backoff.isExhausted(9));
  }

  @Test
  void nonsenseSettingsAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryBackoff(0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryBackoff(3, Duration.ofSeconds(10), Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> backoff.ceiling(0));
  }
}
