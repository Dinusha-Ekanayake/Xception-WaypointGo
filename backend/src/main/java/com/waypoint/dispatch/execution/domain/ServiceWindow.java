package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * When an outlet accepts goods, as it stood when the trip left the dock.
 *
 * <p>Copied onto the delivery record at release, so a later change to reference
 * data never rewrites what a driver was held to. For a mall outlet it is already
 * the overlap of the outlet's window and the mall's (R-PLN-29).
 */
public record ServiceWindow(LocalTime open, LocalTime close) {

  public Instant opensAt(LocalDate serviceDate) {
    return serviceDate.atTime(open).atZone(Clock.OPERATING_ZONE).toInstant();
  }

  public Instant closesAt(LocalDate serviceDate) {
    return serviceDate.atTime(close).atZone(Clock.OPERATING_ZONE).toInstant();
  }
}
