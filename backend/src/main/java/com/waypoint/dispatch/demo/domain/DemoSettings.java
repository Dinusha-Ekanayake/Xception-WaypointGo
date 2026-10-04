package com.waypoint.dispatch.demo.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;

public record DemoSettings(boolean enabled, long offsetSeconds, int simPointIntervalMs,
    int positionFlushMs, boolean banner, int speed) {
  public DemoSettings {
    if (offsetSeconds < -604800 || offsetSeconds > 604800 || (!enabled && offsetSeconds != 0)
        || simPointIntervalMs < 500 || simPointIntervalMs > 10000
        || positionFlushMs < 1000 || positionFlushMs > 60000 || speed < 1 || speed > 60) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid demo clock, interval or speed");
    }
  }
  public static DemoSettings defaults() { return new DemoSettings(false, 0, 2000, 5000, true, 10); }
}
