package com.waypoint.dispatch.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A fixed counting window for MCP rate limits (R-IAM-33): every request in the
 * same minute counts against the same row, and a refused caller is told how
 * long until the next window opens.
 *
 * <p>Fixed, not sliding: a caller can make up to twice the limit across a
 * window boundary. That is the accepted cost of one atomic upsert per request
 * shared by every replica, rather than a log of timestamps per caller.
 */
public record RateWindow(Instant start, Duration size) {
  public static final Duration MINUTE = Duration.ofMinutes(1);

  public static RateWindow containing(Instant now, Duration size) {
    long sizeMillis = size.toMillis();
    long start = Math.floorDiv(now.toEpochMilli(), sizeMillis) * sizeMillis;
    return new RateWindow(Instant.ofEpochMilli(start), size);
  }

  /** Whole seconds until the next window, at least 1, so a Retry-After is never zero. */
  public long secondsUntilNext(Instant now) {
    long millis = Duration.between(now, start.plus(size)).toMillis();
    return Math.max(1, (millis + 999) / 1000);
  }

  /**
   * @param calls this window's count, including the request being decided
   * @return true when the request is over the limit
   */
  public static boolean over(int calls, int limit) {
    return calls > limit;
  }

  /** The first refusal in a window is audited; the rest are only counted, so a flood cannot flood the audit log. */
  public static boolean firstRefusal(int calls, int limit) {
    return calls == limit + 1;
  }
}
