package com.waypoint.dispatch.platform.time;

import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.function.Supplier;

public final class AdjustedClock implements Clock {
  private final Clock base;
  private final Supplier<TimeAdjustment> adjustment;
  public AdjustedClock(Clock base, Supplier<TimeAdjustment> adjustment) {
    this.base = base;
    this.adjustment = adjustment;
  }
  @Override public Instant now() {
    Instant real = base.now();
    try {
      TimeAdjustment source = adjustment.get();
      return source == null ? real : real.plusSeconds(source.offsetSeconds());
    } catch (RuntimeException unavailable) {
      return real;
    }
  }
  @Override public Clock realTime() { return base; }
}
