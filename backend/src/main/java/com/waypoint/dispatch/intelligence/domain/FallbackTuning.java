package com.waypoint.dispatch.intelligence.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the fallback learns from Waypoint's own outcomes (R-ML-14). The
 * deterministic late probability keeps its shape (slack against the delay a
 * route builds up), and two things are fitted on the stops it scored itself
 * that have a trustworthy label (R-ML-12): a calibration (intercept and slope on
 * its log-odds) and, per outlet, a shrunk offset for outlets that run late more
 * or less often than predicted. Too few labels and the tuning is the identity,
 * so the fallback is exactly the documented rule (A-37). Pure.
 */
public final class FallbackTuning {
  /** Labelled stops needed before the calibration is fitted at all. */
  public static final int MIN_LABELS = 200;
  /** Visits an outlet needs before it gets an offset. */
  public static final int MIN_OUTLET_VISITS = 5;
  /** Pseudo-visits that pull an outlet's offset toward zero. */
  static final double OUTLET_PRIOR = 20;
  /** No offset beyond this, in log-odds: one outlet's bad month is not a rule. */
  static final double MAX_OFFSET = 1.5;
  static final double MIN_SLOPE = 0.25;
  static final double MAX_SLOPE = 4.0;
  private static final double EPS = 1e-6;

  private FallbackTuning() {}

  /** One labelled stop the fallback scored. */
  public record Labelled(String outletId, double probability, boolean late) {}

  /** @param labels how many labelled stops it was fitted on; 0 for the identity */
  public record LateTuning(double intercept, double slope, Map<String, Double> outletOffsets, int labels) {
    public static final LateTuning IDENTITY = new LateTuning(0, 1, Map.of(), 0);

    public LateTuning {
      outletOffsets = Map.copyOf(outletOffsets);
    }

    /** The tuned probability for a stop at an outlet. */
    public double apply(double probability, String outletId) {
      double z = intercept + slope * logit(clip(probability)) + outletOffsets.getOrDefault(outletId, 0.0);
      return 1 / (1 + Math.exp(-z));
    }
  }

  public static LateTuning fit(List<Labelled> stops) {
    if (stops.size() < MIN_LABELS) {
      return LateTuning.IDENTITY;
    }
    Optional<ModelMetrics.Calibration> fitted =
        ModelMetrics.calibration(stops.stream().map(s -> new ModelMetrics.Scored(s.probability(), s.late())).toList());
    if (fitted.isEmpty()) {
      return LateTuning.IDENTITY;
    }
    double slope = Math.min(MAX_SLOPE, Math.max(MIN_SLOPE, fitted.get().slope()));
    double intercept = fitted.get().intercept();
    LateTuning calibrated = new LateTuning(intercept, slope, Map.of(), stops.size());

    Map<String, double[]> byOutlet = new HashMap<>();
    for (Labelled s : stops) {
      double[] a = byOutlet.computeIfAbsent(s.outletId(), k -> new double[3]);
      a[0] += 1;
      a[1] += s.late() ? 1 : 0;
      a[2] += calibrated.apply(s.probability(), s.outletId());
    }
    Map<String, Double> offsets = new HashMap<>();
    byOutlet.forEach((outlet, a) -> {
      if (a[0] < MIN_OUTLET_VISITS) {
        return;
      }
      double expected = clip(a[2] / a[0]);
      double observed = clip((a[1] + OUTLET_PRIOR * expected) / (a[0] + OUTLET_PRIOR));
      double offset = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, logit(observed) - logit(expected)));
      if (Math.abs(offset) > 1e-3) {
        offsets.put(outlet, offset);
      }
    });
    return new LateTuning(intercept, slope, offsets, stops.size());
  }

  private static double clip(double p) {
    return Math.min(1 - EPS, Math.max(EPS, p));
  }

  private static double logit(double p) {
    return Math.log(p / (1 - p));
  }
}
