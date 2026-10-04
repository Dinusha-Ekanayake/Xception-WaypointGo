package com.waypoint.dispatch.demo.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;

public record DemoSettings(boolean enabled, long offsetSeconds, int simPointIntervalMs,
    int positionFlushMs, boolean banner, int speed) {
  /** How far the demo clock may move from real time, either way: seven days. */
  public static final long MAX_OFFSET_SECONDS = 604800;

  /** Refuses a target outside the window, in words the control room can show. */
  public static long offsetTo(long offsetSeconds) {
    if (Math.abs(offsetSeconds) > MAX_OFFSET_SECONDS) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "The demo clock stays within 7 days of real time; choose a closer date");
    }
    return offsetSeconds;
  }

  public DemoSettings {
    if (offsetSeconds < -MAX_OFFSET_SECONDS || offsetSeconds > MAX_OFFSET_SECONDS || (!enabled && offsetSeconds != 0)
        || simPointIntervalMs < 500 || simPointIntervalMs > 10000
        || positionFlushMs < 1000 || positionFlushMs > 60000 || speed < 1 || speed > 60) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid demo clock, interval or speed");
    }
  }
  public static DemoSettings defaults() { return new DemoSettings(false, 0, 2000, 5000, true, 10); }
}
