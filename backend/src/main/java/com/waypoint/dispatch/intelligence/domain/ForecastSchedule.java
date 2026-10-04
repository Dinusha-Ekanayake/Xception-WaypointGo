package com.waypoint.dispatch.intelligence.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * When the demand forecast runs (P-29). The job is woken at the top of every
 * hour, depot time, and runs only when {@link #due} says so; {@link #nextRun}
 * walks the same hours forward, so the time the screen shows and the time the
 * job runs come from one rule.
 */
public final class ForecastSchedule {
  /** A fallback run is retried at most this often once a model is active. */
  public static final Duration FALLBACK_RETRY = Duration.ofHours(6);

  /** Far enough to reach the next Monday from any hour of the week. */
  private static final int MAX_HOURS = 8 * 24;

  private ForecastSchedule() {}

  /** The newest run: when it was generated, and whether it was the fallback. */
  public record LastRun(Instant at, boolean degraded) {}

  /**
   * Whether a run is owed at {@code at}: none yet this week (from Monday 00:00
   * in {@code zone}), or the newest run is the fallback, a demand model is
   * active and {@link #FALLBACK_RETRY} has passed since.
   */
  public static boolean due(Instant at, Optional<LastRun> last, boolean modelActive, ZoneId zone) {
    if (last.isEmpty() || last.get().at().isBefore(weekStart(at, zone))) {
      return true;
    }
    return last.get().degraded() && modelActive && !last.get().at().plus(FALLBACK_RETRY).isAfter(at);
  }

  /**
   * The first hourly wake-up after {@code now} at which {@link #due} holds, as
   * things stand: a model activated later can only bring it forward.
   */
  public static Instant nextRun(Instant now, Optional<LastRun> last, boolean modelActive, ZoneId zone) {
    ZonedDateTime tick = now.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1);
    for (int i = 0; i < MAX_HOURS; i++, tick = tick.plusHours(1)) {
      if (due(tick.toInstant(), last, modelActive, zone)) {
        return tick.toInstant();
      }
    }
    return tick.toInstant();
  }

  private static Instant weekStart(Instant at, ZoneId zone) {
    LocalDate monday = at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return monday.atStartOfDay(zone).toInstant();
  }
}
