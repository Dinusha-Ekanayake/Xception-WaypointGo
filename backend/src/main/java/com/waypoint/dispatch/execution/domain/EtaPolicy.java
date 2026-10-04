package com.waypoint.dispatch.execution.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The expected arrival at the stops still to come (R-EXE-15).
 *
 * <p>Deterministic: the delay seen at the latest arrival is carried to every
 * pending stop of the trip. A trip that is ahead of plan is not announced as
 * early; a store prepares for the planned time.
 */
public final class EtaPolicy {
  /** A delay is announced again only once it has moved this far, so a slow road is one message. */
  public static final int ANNOUNCE_STEP_MINUTES = 10;

  private EtaPolicy() {}

  public static int delayMinutes(Instant arrivedAt, Instant plannedArrival) {
    if (!arrivedAt.isAfter(plannedArrival)) {
      return 0;
    }
    return (int) Math.min(Integer.MAX_VALUE, Duration.between(plannedArrival, arrivedAt).toMinutes());
  }

  public static boolean worthAnnouncing(int delayMinutes, int lastAnnouncedMinutes) {
    return Math.abs(delayMinutes - lastAnnouncedMinutes) >= ANNOUNCE_STEP_MINUTES;
  }

  public static Instant expectedArrival(Instant plannedArrival, int delayMinutes) {
    return plannedArrival.plus(Duration.ofMinutes(delayMinutes));
  }
}
