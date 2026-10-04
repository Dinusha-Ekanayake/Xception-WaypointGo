package com.waypoint.dispatch.platform.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * A cron read on the business clock, which demo mode can move (issue #231).
 *
 * <p>A real-time cron trigger cannot follow a clock that jumps, so the scheduler
 * asks this every few seconds instead. It fires once when the cron's next time
 * after the last check has been reached: a jump forward past several fire times
 * fires once, not once per time skipped, and a jump back fires nothing and starts
 * counting again from the new reading.
 */
final class BusinessCronTick {
  private final CronExpression cron;
  private final ZoneId zone;
  private Instant mark;

  BusinessCronTick(String cron, ZoneId zone) {
    this.cron = CronExpression.parse(cron);
    this.zone = zone;
  }

  /** Whether the job is due at {@code now}; the first call only starts the count. */
  synchronized boolean due(Instant now) {
    if (mark == null || now.isBefore(mark)) {
      mark = now;
      return false;
    }
    ZonedDateTime next = cron.next(mark.atZone(zone));
    if (next == null || next.toInstant().isAfter(now)) {
      return false;
    }
    mark = now;
    return true;
  }
}
