package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy.Status;
import java.util.Optional;

/**
 * When a store is warned that its booked day has worsened (issue #224,
 * R-ML-08): only on busy or at risk, and only when worse than any warning
 * already given for that order and day. A day that eases and worsens again is
 * not told twice, so a borderline day does not keep notifying.
 */
public final class OutlookChangePolicy {
  private OutlookChangePolicy() {}

  /** The status to warn of now, or empty when nothing new is worth saying. */
  public static Optional<Status> warn(Optional<Status> alreadyWarned, Status now) {
    int was = alreadyWarned.map(OutlookChangePolicy::severity).orElse(0);
    return severity(now) > was ? Optional.of(now) : Optional.empty();
  }

  /** On track, too early and closed are not warnings; busy is, at risk more so. */
  static int severity(Status status) {
    return switch (status) {
      case BUSY -> 1;
      case AT_RISK -> 2;
      case ON_TRACK, TOO_EARLY, CLOSED -> 0;
    };
  }
}
