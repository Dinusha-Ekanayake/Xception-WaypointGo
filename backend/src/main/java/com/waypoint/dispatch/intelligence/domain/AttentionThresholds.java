package com.waypoint.dispatch.intelligence.domain;

/**
 * The numbers {@link AttentionPolicy} and {@link ReminderPolicy} read (issue
 * #268). They are data: a depot's row in {@code ml.attention_thresholds}
 * replaces these defaults with no deployment.
 *
 * @param lateMinutes how late against the plan before a stop is worth knowing about
 * @param windowWarnMinutes a window closing within this many minutes makes an at-risk stop critical
 * @param proofGraceMinutes how long a driver has to add proof before it is chased
 * @param remindAfterMinutes how long an unacknowledged critical item waits before a reminder
 * @param maxReminders the most reminders one item gets
 */
public record AttentionThresholds(
    int lateMinutes, int windowWarnMinutes, int proofGraceMinutes, int remindAfterMinutes, int maxReminders) {

  public static final AttentionThresholds DEFAULT = new AttentionThresholds(15, 60, 10, 10, 2);

  public AttentionThresholds {
    if (lateMinutes < 1 || windowWarnMinutes < 1 || proofGraceMinutes < 0 || remindAfterMinutes < 1 || maxReminders < 0) {
      throw new IllegalArgumentException("attention thresholds must be positive");
    }
  }
}
