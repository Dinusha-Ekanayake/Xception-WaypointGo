package com.waypoint.dispatch.shared.util;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Time as a dependency. Architecture rule 2: the domain never reads the system
 * clock, so every rule that depends on time is deterministically testable.
 */
@FunctionalInterface
public interface Clock {
  ZoneId OPERATING_ZONE = ZoneId.of("Asia/Colombo");

  Instant now();

  static Clock system() {
    return Instant::now;
  }

  static Clock fixed(Instant instant) {
    return () -> instant;
  }
}
