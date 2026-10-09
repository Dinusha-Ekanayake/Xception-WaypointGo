package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A range around a stop's expected unloading minutes (roadmap MF-06, R-ML-20),
 * from how the same predictor missed on earlier days: the ratio of what was
 * taken to what was predicted, over stops before the plan's day only, so the
 * range is out of time. The outlet's own misses when it has {@link #MIN_RATIOS};
 * else the depot's; else every depot's; else no range at all, never a guess.
 * The range is the 10th to 90th percentile, so about four stops in five fall
 * inside it. Pure.
 */
public final class ServiceRange {
  public static final int MIN_RATIOS = 30;
  static final double LOW = 0.10;
  static final double HIGH = 0.90;

  private ServiceRange() {}

  public enum Basis {
    OUTLET,
    DEPOT,
    ALL
  }

  public record Range(double low, double high, Basis basis) {}

  /** One earlier stop: where it was and how its minutes compared with the prediction. */
  public record Miss(String outletId, String depotCode, double ratio) {}

  /** The ratios grouped once, so each stop of a plan looks its bucket up. */
  public record Misses(Map<String, List<Double>> byOutlet, Map<String, List<Double>> byDepot, List<Double> all) {
    public static Misses of(Collection<Miss> misses) {
      Map<String, List<Double>> outlets = new java.util.HashMap<>();
      Map<String, List<Double>> depots = new java.util.HashMap<>();
      List<Double> all = new ArrayList<>();
      for (Miss m : misses) {
        if (!(m.ratio() > 0) || Double.isInfinite(m.ratio())) {
          continue;
        }
        outlets.computeIfAbsent(m.outletId(), k -> new ArrayList<>()).add(m.ratio());
        depots.computeIfAbsent(m.depotCode(), k -> new ArrayList<>()).add(m.ratio());
        all.add(m.ratio());
      }
      return new Misses(outlets, depots, all);
    }
  }

  /** The range for a stop expected to take {@code minutes}, or empty with too few earlier stops. */
  public static Optional<Range> of(double minutes, String outletId, String depotCode, Misses misses) {
    if (!(minutes > 0)) {
      return Optional.empty();
    }
    List<Double> outlet = misses.byOutlet().getOrDefault(outletId, List.of());
    List<Double> depot = misses.byDepot().getOrDefault(depotCode, List.of());
    if (outlet.size() >= MIN_RATIOS) {
      return Optional.of(range(minutes, outlet, Basis.OUTLET));
    }
    if (depot.size() >= MIN_RATIOS) {
      return Optional.of(range(minutes, depot, Basis.DEPOT));
    }
    if (misses.all().size() >= MIN_RATIOS) {
      return Optional.of(range(minutes, misses.all(), Basis.ALL));
    }
    return Optional.empty();
  }

  private static Range range(double minutes, List<Double> ratios, Basis basis) {
    double[] sorted = ratios.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    // The expected minutes always sit inside their own range.
    double low = Math.min(minutes, minutes * quantile(sorted, LOW));
    double high = Math.max(minutes, minutes * quantile(sorted, HIGH));
    return new Range(round(low), round(high), basis);
  }

  /** Linear interpolation between order statistics (type 7, as numpy's default). */
  static double quantile(double[] sorted, double q) {
    double h = (sorted.length - 1) * q;
    int i = (int) Math.floor(h);
    return i + 1 < sorted.length ? sorted[i] + (h - i) * (sorted[i + 1] - sorted[i]) : sorted[i];
  }

  private static double round(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
