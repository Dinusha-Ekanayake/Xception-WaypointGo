package com.waypoint.dispatch.intelligence.domain;

import java.util.List;
import java.util.Optional;

/**
 * The forecast every model has to beat (roadmap T2-03, R-ML-21): the mean
 * weekly volume ordered over the 8 weeks before the week, which is the
 * 8-week same-weekday mean summed over the week. Weeks with no orders at all
 * are taken as weeks before the series began, not as zero demand; with fewer
 * than {@link #MIN_WEEKS} weeks that had orders there is no baseline. Pure.
 */
public final class ForecastBaseline {
  public static final int WEEKS = 8;
  public static final int MIN_WEEKS = 4;

  private ForecastBaseline() {}

  /** @param weeklyTotals the ordered volume of each of the 8 weeks before, oldest first */
  public static Optional<Double> of(List<Double> weeklyTotals) {
    List<Double> ordered = weeklyTotals.stream().filter(v -> v != null && v > 0).toList();
    if (ordered.size() < MIN_WEEKS) {
      return Optional.empty();
    }
    return Optional.of(ordered.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
  }
}
