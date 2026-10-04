package com.waypoint.dispatch.platform.scheduling;

import com.waypoint.dispatch.platform.db.ModuleRole;
import java.time.Instant;

/**
 * Work that runs on a timetable rather than in response to a request: the order
 * cutoff, receipt auto-close, audit partition creation, retention.
 *
 * <p>The scheduler takes an advisory-lock lease per job before calling
 * {@link #run}, so with several replicas exactly one runs a given job at a time
 * (edge case PLT-04). The job receives the scheduler's clock reading rather than
 * reading the clock itself, which is what makes it testable. That reading is the
 * business clock, so it follows the demo clock when demo mode moves it.
 */
public interface ScheduledJob {

  /** Stable and unique; it is also the lease key. */
  String name();

  /** A Spring cron expression, evaluated in the depot timezone {@code Asia/Colombo}. */
  String cron();

  ModuleRole moduleRole();

  void run(Instant now);

  /**
   * True when the cron names a business hour (the 16:00 cutoff, the 06:00
   * calendar check), so it is read on the business clock: moving the demo clock
   * past 16:00 closes ordering then, not at the real 16:00. False, the default,
   * for jobs that run every few seconds or minutes, where the hour does not matter.
   */
  default boolean onBusinessClock() {
    return false;
  }
}
