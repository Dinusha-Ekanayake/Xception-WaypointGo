package com.waypoint.dispatch.execution.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * What an arrival time means against the outlet's window.
 *
 * <ul>
 *   <li>R-EXE-04: an early arrival waits. Service starts at the later of arrival
 *       and window open, and the wait is counted apart from service, so a long
 *       wait never inflates the service-time history (EXE-05, EXE-18).
 *   <li>R-EXE-14: late means after the window <b>closes</b>. The planned arrival
 *       is not consulted: a stop can be behind plan and on time (EXE-16), or on
 *       plan and late (EXE-17).
 * </ul>
 *
 * <p>Minutes are rounded up, so one second past the close is one minute late
 * rather than on time.
 */
public final class LatenessPolicy {
  private LatenessPolicy() {}

  public static int waitMinutes(Instant arrivedAt, Instant windowOpens) {
    return arrivedAt.isBefore(windowOpens) ? minutesUp(Duration.between(arrivedAt, windowOpens)) : 0;
  }

  public static int lateMinutes(Instant arrivedAt, Instant windowCloses) {
    return arrivedAt.isAfter(windowCloses) ? minutesUp(Duration.between(windowCloses, arrivedAt)) : 0;
  }

  public static Instant serviceStart(Instant arrivedAt, Instant windowOpens) {
    return arrivedAt.isBefore(windowOpens) ? windowOpens : arrivedAt;
  }

  private static int minutesUp(Duration d) {
    return (int) Math.min(Integer.MAX_VALUE, (d.toSeconds() + 59) / 60);
  }
}
