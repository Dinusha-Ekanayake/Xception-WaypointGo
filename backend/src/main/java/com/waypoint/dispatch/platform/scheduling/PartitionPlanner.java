package com.waypoint.dispatch.platform.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which audit partitions should exist, and which have aged out.
 *
 * <p>Pure: the clock is a parameter. Months are UTC, which is how
 * {@code integration.ensure_audit_partition} cuts them, so the planner and the
 * database cannot disagree about where a month starts.
 */
public final class PartitionPlanner {
  private static final Pattern NAME = Pattern.compile("^audit_log_(\\d{4})_(\\d{2})$");

  private PartitionPlanner() {}

  /** The first day of {@code now}'s month and each of the {@code ahead} months after it. */
  public static List<LocalDate> required(Instant now, int ahead) {
    LocalDate current = firstOfMonth(now);
    List<LocalDate> months = new ArrayList<>();
    for (int i = 0; i <= ahead; i++) {
      months.add(current.plusMonths(i));
    }
    return months;
  }

  /** The month a partition name covers, or null when it is not a monthly audit partition. */
  public static LocalDate monthOf(String partitionName) {
    Matcher m = NAME.matcher(partitionName);
    if (!m.matches()) {
      return null;
    }
    int month = Integer.parseInt(m.group(2));
    return month < 1 || month > 12 ? null : LocalDate.of(Integer.parseInt(m.group(1)), month, 1);
  }

  /** How many of {@code existing} cover a month after {@code now}'s. */
  public static int futureCount(Instant now, List<String> existing) {
    LocalDate current = firstOfMonth(now);
    return (int)
        existing.stream()
            .map(PartitionPlanner::monthOf)
            .filter(month -> month != null && month.isAfter(current))
            .count();
  }

  /**
   * Partitions wholly older than {@code retentionMonths}. A month is kept while
   * any part of it is inside the window, so the oldest data kept is never younger
   * than the promise. Empty when retention is zero, which keeps everything.
   */
  public static List<String> expired(Instant now, int retentionMonths, List<String> existing) {
    if (retentionMonths <= 0) {
      return List.of();
    }
    LocalDate cutoff = firstOfMonth(now).minusMonths(retentionMonths);
    return existing.stream()
        .filter(name -> monthOf(name) != null && monthOf(name).isBefore(cutoff))
        .sorted()
        .toList();
  }

  private static LocalDate firstOfMonth(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
  }
}
