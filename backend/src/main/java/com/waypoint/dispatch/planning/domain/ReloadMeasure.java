package com.waypoint.dispatch.planning.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The reload time a depot actually takes (R-PLN-47), from its released second
 * and later trips: the median, and the minutes three reloads in four stay within.
 * The rule set's {@code trip.reload.min} is a placeholder until it is set from
 * this; the screen shows both side by side, and the administrator decides.
 *
 * <p>Pure: the samples are minutes. Fewer than {@link #MIN_SAMPLES} say nothing,
 * because a handful of reloads on a quiet week would set the rule for every day.
 */
public final class ReloadMeasure {
  public static final int MIN_SAMPLES = 10;

  public record Measure(String depotCode, long median, long p75, int samples) {}

  private ReloadMeasure() {}

  /** One measure per depot with enough reloads, depots in name order. */
  public static List<Measure> byDepot(Map<String, List<Long>> minutesByDepot) {
    List<Measure> out = new ArrayList<>();
    new TreeMap<>(minutesByDepot).forEach((depot, minutes) -> of(depot, minutes).ifPresent(out::add));
    return out;
  }

  public static Optional<Measure> of(String depotCode, List<Long> minutes) {
    List<Long> valid = minutes.stream().filter(m -> m != null && m > 0).sorted().toList();
    if (valid.size() < MIN_SAMPLES) return Optional.empty();
    return Optional.of(new Measure(depotCode, percentile(valid, 50), percentile(valid, 75), valid.size()));
  }

  /** Nearest rank: the smallest value with at least p percent of the samples at or below it. */
  static long percentile(List<Long> sorted, int p) {
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(0, rank - 1));
  }
}
