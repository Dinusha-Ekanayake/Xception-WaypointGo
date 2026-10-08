package com.waypoint.dispatch.intelligence.domain;

import java.util.Collection;
import java.util.Optional;

/**
 * A range around a weekly volume forecast (roadmap MF-06, R-ML-20): how far
 * finished weeks' forecasts missed, as the log of ordered over forecast, pooled
 * across every depot and brand series, since one series has too few weeks on
 * its own. The 10th to 90th percentile, so about four weeks in five fall
 * inside; with fewer than {@link #MIN_WEEKS} finished pairs, no range. Pure.
 */
public final class ForecastRange {
  public static final int MIN_WEEKS = 30;

  private ForecastRange() {}

  /** Multiply a forecast by these to get its range. */
  public record Factors(double low, double high, int weeks) {}

  /** One finished week of one series: what was forecast before it, and what was ordered. */
  public record Pair(double forecastM3, double orderedM3) {}

  public static Optional<Factors> factors(Collection<Pair> pairs) {
    double[] logs = pairs.stream()
        .filter(p -> p.forecastM3() > 0 && p.orderedM3() > 0)
        .mapToDouble(p -> Math.log(p.orderedM3() / p.forecastM3()))
        .sorted()
        .toArray();
    if (logs.length < MIN_WEEKS) {
      return Optional.empty();
    }
    return Optional.of(new Factors(
        Math.exp(ServiceRange.quantile(logs, ServiceRange.LOW)),
        Math.exp(ServiceRange.quantile(logs, ServiceRange.HIGH)),
        logs.length));
  }

  /** The forecast's range; the forecast itself always lies inside it. */
  public static double[] apply(double forecastM3, Factors f) {
    return new double[] {
      Math.min(forecastM3, forecastM3 * f.low()), Math.max(forecastM3, forecastM3 * f.high())
    };
  }
}
