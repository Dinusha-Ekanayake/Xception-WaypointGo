package com.waypoint.dispatch.platform.messaging;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * When a failed delivery is tried again, and when it is given up on.
 *
 * <p>Exponential with full jitter: the delay is drawn uniformly from zero up to
 * {@code min(cap, base * 2^(failures-1))}. Without jitter every event that failed
 * in the same outage retries at the same instant and fails the recovering
 * dependency again. Randomness is a parameter, so a test can pin it.
 */
public record RetryBackoff(int maxAttempts, Duration base, Duration cap) {

  public RetryBackoff {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("base must be positive and cap no shorter than base");
    }
  }

  /** True once {@code failures} deliveries have failed: this event goes to dead letter. */
  public boolean isExhausted(int failures) {
    return failures >= maxAttempts;
  }

  /** The ceiling of the delay after the {@code failures}th failure, before jitter. */
  public Duration ceiling(int failures) {
    if (failures < 1) {
      throw new IllegalArgumentException("failures counts from 1");
    }
    // Past 40 doublings the shift overflows a long long before the cap matters.
    long factor = 1L << Math.min(failures - 1, 40);
    long capMillis = cap.toMillis();
    long baseMillis = base.toMillis();
    return Duration.ofMillis(baseMillis > capMillis / factor ? capMillis : baseMillis * factor);
  }

  /** @param random uniform in [0, 1) */
  public Duration delay(int failures, DoubleSupplier random) {
    return Duration.ofMillis((long) (ceiling(failures).toMillis() * random.getAsDouble()));
  }
}
