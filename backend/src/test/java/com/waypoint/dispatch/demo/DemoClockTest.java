package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.waypoint.dispatch.platform.time.AdjustedClock;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DemoClockTest {
  private final Instant now = Instant.parse("2026-10-04T09:00:00Z");
  @Test void businessTimeMovesButSecurityTimeDoesNot() {
    var clock = new AdjustedClock(Clock.fixed(now), () -> () -> 3600);
    assertEquals(now.plusSeconds(3600), clock.now());
    assertEquals(now, clock.realTime().now());
  }
  @Test void missingOrUnreadableSettingsUseRealTime() {
    assertEquals(now, new AdjustedClock(Clock.fixed(now), () -> null).now());
    assertEquals(now, new AdjustedClock(Clock.fixed(now), () -> () -> { throw new IllegalStateException(); }).now());
  }
}
