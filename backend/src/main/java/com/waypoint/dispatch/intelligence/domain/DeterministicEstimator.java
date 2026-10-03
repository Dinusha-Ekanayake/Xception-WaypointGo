package com.waypoint.dispatch.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The estimates Waypoint can always give, with no model: what the system falls
 * back to when the model service is down, unconfigured or not the active
 * version (R-ML-04), and what it uses before any model is registered.
 *
 * <p>Every constant here is a documented assumption (ASSUMPTIONS A-36 to A-38),
 * not a fitted parameter. A learned model replaces all three.
 */
public final class DeterministicEstimator {
  /** Below this many recent visits, an outlet's own history is too thin to beat the allowance. */
  public static final int MIN_HISTORY = 5;

  /**
   * The departure delay every route starts with, in minutes. Measured in the
   * Datathon training data (planned versus actual first departure, A-37).
   */
  static final double START_DELAY_MIN = 7.7;

  /** Delay that each further stop adds, because lateness compounds along a route (A-37). */
  static final double DRIFT_PER_STOP_MIN = 2.0;

  /** How sharply the probability rises as the slack runs out, in minutes (A-37). */
  static final double SCALE_MIN = 6.0;

  static final double FLOOR = 0.0005;

  private DeterministicEstimator() {}

  /** @param basis {@code history}, the outlet's own median, or {@code allowance}, the booklet budget */
  public record ServiceEstimate(BigDecimal minutes, String basis) {}

  /**
   * The outlet's median actual service over its recent visits, or the service
   * allowance when there are fewer than {@link #MIN_HISTORY}. The allowance is a
   * planning budget, not an observed duration (A-16), so history wins whenever
   * there is enough of it.
   */
  public static ServiceEstimate serviceMinutes(List<BigDecimal> recentServiceMinutes, BigDecimal allowance) {
    List<BigDecimal> sorted =
        recentServiceMinutes.stream().filter(m -> m != null && m.signum() >= 0).sorted().toList();
    if (sorted.size() < MIN_HISTORY) {
      return new ServiceEstimate(allowance.setScale(2, RoundingMode.HALF_UP), "allowance");
    }
    int mid = sorted.size() / 2;
    BigDecimal median =
        sorted.size() % 2 == 1
            ? sorted.get(mid)
            : sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    return new ServiceEstimate(median.setScale(2, RoundingMode.HALF_UP), "history");
  }

  /**
   * P(arriving after the window closes): a logistic in the slack between the
   * planned arrival and the close, against the delay a route is expected to
   * have built up by that stop.
   *
   * @param stopIndex 0 for the first stop of a trip
   */
  public static BigDecimal lateProbability(LocalTime plannedArrival, LocalTime windowClose, int stopIndex) {
    double slack = Duration.between(plannedArrival, windowClose).toSeconds() / 60.0;
    double expectedDelay = START_DELAY_MIN + DRIFT_PER_STOP_MIN * Math.max(0, stopIndex);
    double p = 1.0 / (1.0 + Math.exp((slack - expectedDelay) / SCALE_MIN));
    p = Math.min(1 - FLOOR, Math.max(FLOOR, p));
    return BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_UP);
  }

  /** One day of placed demand. */
  public record DailyVolume(LocalDate date, BigDecimal totalM3, BigDecimal chilledM3) {}

  public record WeekVolume(BigDecimal totalM3, BigDecimal chilledM3) {}

  /**
   * A week's demand as the mean of the same weekdays over the eight weeks before
   * {@code origin}, summed over the week's operating days. Fresh and Style order
   * on fixed weekday schedules, so the weekday is what carries the signal; a
   * closed day gets nothing, because orders on closed days are lost, not moved
   * (the Datathon's finding, A-38).
   */
  public static WeekVolume weekForecast(
      List<DailyVolume> history, LocalDate origin, List<LocalDate> operatingDaysOfWeek) {
    LocalDate from = origin.minusWeeks(8);
    Map<DayOfWeek, BigDecimal[]> sums = new EnumMap<>(DayOfWeek.class);
    for (DailyVolume d : history) {
      if (d.date().isBefore(from) || !d.date().isBefore(origin)) {
        continue;
      }
      BigDecimal[] s = sums.computeIfAbsent(d.date().getDayOfWeek(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      s[0] = s[0].add(d.totalM3());
      s[1] = s[1].add(d.chilledM3());
    }
    BigDecimal weeks = BigDecimal.valueOf(8);
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal chilled = BigDecimal.ZERO;
    for (LocalDate day : new ArrayList<>(operatingDaysOfWeek)) {
      BigDecimal[] s = sums.get(day.getDayOfWeek());
      if (s != null) {
        total = total.add(s[0].divide(weeks, 6, RoundingMode.HALF_UP));
        chilled = chilled.add(s[1].divide(weeks, 6, RoundingMode.HALF_UP));
      }
    }
    return new WeekVolume(total.setScale(4, RoundingMode.HALF_UP), chilled.min(total).setScale(4, RoundingMode.HALF_UP));
  }
}
