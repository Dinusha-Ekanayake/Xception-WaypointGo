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
 * reading the clock itself, which is what makes it testable.
 */
public interface ScheduledJob {

  /** Stable and unique; it is also the lease key. */
  String name();

  /** A Spring cron expression, evaluated in the depot timezone {@code Asia/Colombo}. */
  String cron();

  ModuleRole moduleRole();

  void run(Instant now);
}
