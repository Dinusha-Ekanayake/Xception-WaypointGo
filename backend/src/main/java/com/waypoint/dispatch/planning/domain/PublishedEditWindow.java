package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * How long a published plan takes changes (R-PLN-43). Once published it is the
 * day's only plan; it can still be revised or have a trip moved until the next
 * daily cutoff, 16:00 depot time on its service day, when the next day's
 * ordering closes. After that it is final. Evaluated on the server clock in
 * {@code Asia/Colombo}, never on a client timestamp.
 */
public final class PublishedEditWindow {
  public static final LocalTime CLOSES = LocalTime.of(16, 0);

  private PublishedEditWindow() {}

  /** The instant a published plan for {@code serviceDate} stops taking changes. */
  public static Instant closesAt(LocalDate serviceDate) {
    return ZonedDateTime.of(serviceDate, CLOSES, Clock.OPERATING_ZONE).toInstant();
  }

  public static boolean isOpen(LocalDate serviceDate, Instant now) {
    return now.isBefore(closesAt(serviceDate));
  }

  /** Refuses a change to a published plan once its window has closed. */
  public static void requireOpen(LocalDate serviceDate, Instant now) {
    if (!isOpen(serviceDate, now)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "the published plan for " + serviceDate + " took its last change at 16:00 that day; it is final",
          List.of("R-PLN-43"));
    }
  }
}
