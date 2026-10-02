package com.waypoint.dispatch.platform.messaging;

import java.time.Duration;

/**
 * How long a failed event waits before its next delivery.
 *
 * <p>Exponential from a base, capped, with jitter so that events which failed
 * together (a dependency went down) do not all come back together. Randomness is
 * a parameter, so the arithmetic is tested without a clock or a database.
 */
public final class RelayBackoff {
  /** Each wait is spread across this fraction either side of its nominal value. */
  static final double JITTER = 0.2;

  private RelayBackoff() {}

  /**
   * @param attempts deliveries tried so far, at least one
   * @param random uniform in [0, 1)
   */
  public static Duration after(int attempts, Duration base, Duration cap, double random) {
    // Doubling 30 times already exceeds any cap worth configuring, and stops the shift overflowing.
    int doublings = Math.min(Math.max(attempts, 1) - 1, 30);
    long nominal = Math.min(base.toMillis() * (1L << doublings), cap.toMillis());
    double spread = 1 - JITTER + 2 * JITTER * random;
    return Duration.ofMillis(Math.max(1, Math.round(nominal * spread)));
  }
}
