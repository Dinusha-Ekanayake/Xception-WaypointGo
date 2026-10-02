package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.infrastructure.ReferenceCache;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Warns before the supplied calendar runs out (PLT-07).
 *
 * <p>Past its last day the calendar is extended by a policy (Monday to Saturday),
 * which is a guess about holidays nobody has announced yet. The gauge
 * {@code waypoint.reference.calendar.days_remaining} shows the margin; this job is
 * the alert, because a gauge nobody looks at warns nobody. Once a day it logs a
 * warning and counts {@code waypoint.reference.calendar.exhaustion_warning} while
 * the margin is under the threshold.
 *
 * <p>Reads the snapshot this instance holds, so it needs no database access and
 * never fails because the database is down.
 */
@Component
public class CalendarExhaustionJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(CalendarExhaustionJob.class);

  private final ReferenceCache cache;
  private final Metrics metrics;
  private final int warnWithinDays;

  public CalendarExhaustionJob(
      ReferenceCache cache,
      Metrics metrics,
      @Value("${app.reference.calendar-warning-days:30}") int warnWithinDays) {
    this.cache = cache;
    this.metrics = metrics;
    this.warnWithinDays = warnWithinDays;
  }

  @Override
  public String name() {
    return "reference.calendar-exhaustion";
  }

  @Override
  public String cron() {
    return "0 0 6 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.REF;
  }

  @Override
  public void run(Instant now) {
    long remaining =
        cache
            .current()
            .map(
                snapshot ->
                    snapshot.calendarDaysRemaining(
                        LocalDate.ofInstant(now, com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE)))
            .orElse(0L);
    if (remaining < warnWithinDays) {
      metrics.increment("waypoint.reference.calendar.exhaustion_warning");
      log.warn(
          "The supplied calendar covers {} more day(s). Import a longer one before it runs out;"
              + " after that, days come from the extension policy.",
          remaining);
    }
  }
}
