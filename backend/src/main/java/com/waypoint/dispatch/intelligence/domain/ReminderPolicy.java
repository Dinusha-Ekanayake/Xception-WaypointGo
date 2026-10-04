package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When the dispatcher is reminded of something they have not acknowledged
 * (issue #268). Only what is critical is chased, never before the waiting time
 * has passed since it was raised or last reminded, and never more than the
 * cap: a long incident must not become noise.
 */
public final class ReminderPolicy {
  private ReminderPolicy() {}

  public static boolean due(
      Severity severity,
      Instant raisedAt,
      Optional<Instant> lastRemindedAt,
      int remindedCount,
      AttentionThresholds thresholds,
      Instant now) {
    if (severity != Severity.CRITICAL || remindedCount >= thresholds.maxReminders()) {
      return false;
    }
    Instant since = lastRemindedAt.orElse(raisedAt);
    return Duration.between(since, now).toMinutes() >= thresholds.remindAfterMinutes();
  }
}
