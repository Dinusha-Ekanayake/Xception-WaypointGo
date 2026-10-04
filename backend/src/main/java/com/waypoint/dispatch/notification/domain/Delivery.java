package com.waypoint.dispatch.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One channel a notification went out on, and what happens after each attempt.
 *
 * <p>The in-app inbox is delivered the moment the notification is written: the
 * row is the delivery. A push starts {@code PENDING} and is tried until the push
 * service accepts it ({@code SENT}), says the subscription is gone, or the
 * attempts run out ({@code DEAD}). A dead delivery stays, with its last error, so
 * a person can see that nothing reached the device (PLT-03).
 */
public final class Delivery {
  private Delivery() {}

  public enum Channel {
    IN_APP,
    PUSH;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Status {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
    DEAD;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Status parse(String code) {
      return valueOf(code.toUpperCase(Locale.ROOT));
    }

    public boolean isDue() {
      return this == PENDING || this == FAILED;
    }
  }

  /** What the push service answered. */
  public enum PushResult {
    /** 201: the push service has it. */
    ACCEPTED,
    /** 404 or 410: the subscription no longer exists (NOT-01). */
    GONE,
    /** 429, 5xx, a timeout or no connection: worth trying again. */
    RETRYABLE,
    /** Any other 4xx: the request itself is wrong, and repeating it changes nothing. */
    REJECTED
  }

  /** How many attempts a push gets, and how long it waits between them. */
  public record RetryPolicy(int maxAttempts, Duration base, Duration cap) {
    /** Each wait is spread across this fraction either side of its nominal value. */
    static final double JITTER = 0.2;

    public RetryPolicy {
      if (maxAttempts < 1 || base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
        throw new IllegalArgumentException("a retry policy needs one attempt and 0 < base <= cap");
      }
    }

    /**
     * Exponential from the base, capped, with jitter so pushes that failed
     * together do not all return together.
     *
     * @param attempts tried so far, at least one
     * @param random uniform in [0, 1)
     */
    public Duration backoff(int attempts, double random) {
      int doublings = Math.min(Math.max(attempts, 1) - 1, 30);
      long nominal = Math.min(base.toMillis() * (1L << doublings), cap.toMillis());
      double spread = 1 - JITTER + 2 * JITTER * random;
      return Duration.ofMillis(Math.max(1, Math.round(nominal * spread)));
    }
  }

  /**
   * The state after one attempt.
   *
   * @param attempts including this one
   * @param nextAttemptAt when the next try is due, present only while it is
   * @param expireSubscription the push service said the browser is gone
   */
  public record Outcome(
      Status status, int attempts, Optional<Instant> nextAttemptAt, boolean expireSubscription) {}

  /**
   * @param attemptsBefore tried before this attempt
   * @param now when this attempt finished
   */
  public static Outcome after(
      PushResult result, int attemptsBefore, RetryPolicy policy, Instant now, double random) {
    int attempts = attemptsBefore + 1;
    return switch (result) {
      case ACCEPTED -> new Outcome(Status.SENT, attempts, Optional.empty(), false);
      case GONE -> new Outcome(Status.DEAD, attempts, Optional.empty(), true);
      case REJECTED -> new Outcome(Status.DEAD, attempts, Optional.empty(), false);
      case RETRYABLE ->
          attempts >= policy.maxAttempts()
              ? new Outcome(Status.DEAD, attempts, Optional.empty(), false)
              : new Outcome(
                  Status.FAILED, attempts, Optional.of(now.plus(policy.backoff(attempts, random))), false);
    };
  }

  /** The HTTP status a push service answered, as a result. */
  public static PushResult classify(int httpStatus) {
    if (httpStatus >= 200 && httpStatus < 300) {
      return PushResult.ACCEPTED;
    }
    if (httpStatus == 404 || httpStatus == 410) {
      return PushResult.GONE;
    }
    if (httpStatus == 429 || httpStatus == 408 || httpStatus >= 500) {
      return PushResult.RETRYABLE;
    }
    return PushResult.REJECTED;
  }
}
