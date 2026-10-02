package com.waypoint.dispatch.ordering.domain;

import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * The 16:00 ordering cutoff (R-ORD-01), evaluated on the server clock in
 * {@code Asia/Colombo} and never on a client timestamp (R-ORD-07).
 *
 * <p>Orders for service date D close at 16:00 on the calendar day before D. The
 * day before, not the operating day before: a store may prepare Monday's order on
 * Sunday (decision D-I), so Monday's run closes at 16:00 on Sunday.
 */
public final class Cutoff {
  public static final LocalTime TIME = LocalTime.of(16, 0);

  private Cutoff() {}

  /** The instant ordering for {@code serviceDate} closes. */
  public static Instant closesAt(LocalDate serviceDate) {
    return ZonedDateTime.of(serviceDate.minusDays(1), TIME, Clock.OPERATING_ZONE).toInstant();
  }

  /** True once ordering for {@code serviceDate} has closed at {@code now}. */
  public static boolean hasPassed(LocalDate serviceDate, Instant now) {
    return !now.isBefore(closesAt(serviceDate));
  }

  /**
   * The first service date an order placed at {@code now} can still make:
   * tomorrow before 16:00, the day after from 16:00 exactly (ORD-01).
   */
  public static LocalDate earliestServiceDate(Instant now) {
    ZonedDateTime local = now.atZone(Clock.OPERATING_ZONE);
    LocalDate tomorrow = local.toLocalDate().plusDays(1);
    return local.toLocalTime().isBefore(TIME) ? tomorrow : tomorrow.plusDays(1);
  }
}
