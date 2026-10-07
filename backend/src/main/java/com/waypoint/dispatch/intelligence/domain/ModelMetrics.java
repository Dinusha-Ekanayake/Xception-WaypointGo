package com.waypoint.dispatch.intelligence.domain;

import java.util.List;
import java.util.Optional;

/**
 * How well a model did on a day's outcomes (roadmap MF-03, R-ML-13). Pure.
 *
 * <p>Lateness: the Brier score and the log loss of the predicted probability
 * against the label, and the calibration slope and intercept (the logistic fit
 * of the label on the prediction's log-odds: slope 1 and intercept 0 is
 * perfectly calibrated). Service minutes: the mean absolute error. Only labelled
 * stops count for lateness (R-ML-12); equal-bin calibration error is too noisy
 * at this volume and is not used.
 */
public final class ModelMetrics {
  /** Fewer labelled stops than this, or only one class among them, and calibration is not fitted. */
  public static final int MIN_FOR_CALIBRATION = 30;
  private static final double EPS = 1e-6;

  private ModelMetrics() {}

  /** One labelled stop: the predicted probability of lateness, and whether it was late. */
  public record Scored(double probability, boolean late) {}

  public record Calibration(double slope, double intercept) {}

  public static Optional<Double> brier(List<Scored> s) {
    if (s.isEmpty()) {
      return Optional.empty();
    }
    double sum = 0;
    for (Scored x : s) {
      double d = x.probability() - (x.late() ? 1 : 0);
      sum += d * d;
    }
    return Optional.of(sum / s.size());
  }

  public static Optional<Double> logLoss(List<Scored> s) {
    if (s.isEmpty()) {
      return Optional.empty();
    }
    double sum = 0;
    for (Scored x : s) {
      double p = clip(x.probability());
      sum -= x.late() ? Math.log(p) : Math.log(1 - p);
    }
    return Optional.of(sum / s.size());
  }

  /** Newton's method on the two-parameter logistic fit; empty when there is too little to fit. */
  public static Optional<Calibration> calibration(List<Scored> s) {
    long late = s.stream().filter(Scored::late).count();
    if (s.size() < MIN_FOR_CALIBRATION || late == 0 || late == s.size()) {
      return Optional.empty();
    }
    double a = 0;
    double b = 1;
    for (int iteration = 0; iteration < 50; iteration++) {
      double ga = 0, gb = 0, haa = 0, hab = 0, hbb = 0;
      for (Scored x : s) {
        double z = logit(clip(x.probability()));
        double q = 1 / (1 + Math.exp(-(a + b * z)));
        double r = (x.late() ? 1 : 0) - q;
        double w = q * (1 - q);
        ga += r;
        gb += r * z;
        haa += w;
        hab += w * z;
        hbb += w * z * z;
      }
      double det = haa * hbb - hab * hab;
      if (Math.abs(det) < 1e-12) {
        return Optional.empty();
      }
      double da = (hbb * ga - hab * gb) / det;
      double db = (haa * gb - hab * ga) / det;
      a += da;
      b += db;
      if (Math.abs(da) < 1e-9 && Math.abs(db) < 1e-9) {
        break;
      }
    }
    return Double.isFinite(a) && Double.isFinite(b) ? Optional.of(new Calibration(b, a)) : Optional.empty();
  }

  /** Mean absolute error of predicted against actual minutes. */
  public static Optional<Double> meanAbsoluteError(List<double[]> predictedActual) {
    if (predictedActual.isEmpty()) {
      return Optional.empty();
    }
    double sum = 0;
    for (double[] pa : predictedActual) {
      sum += Math.abs(pa[0] - pa[1]);
    }
    return Optional.of(sum / predictedActual.size());
  }

  private static double clip(double p) {
    return Math.min(1 - EPS, Math.max(EPS, p));
  }

  private static double logit(double p) {
    return Math.log(p / (1 - p));
  }

  /**
   * Weighted absolute percentage error of forecasts against actuals:
   * {@code sum|f - a| / sum a}. Empty when nothing was ordered, so an empty week
   * never reads as perfect.
   */
  public static Optional<Double> wape(List<double[]> forecastActual) {
    double error = 0;
    double actual = 0;
    for (double[] fa : forecastActual) {
      error += Math.abs(fa[0] - fa[1]);
      actual += Math.abs(fa[1]);
    }
    return actual <= 0 ? Optional.empty() : Optional.of(error / actual);
  }

  /** Fewer values than this on either side and drift is not measured. */
  public static final int MIN_FOR_PSI = 100;

  /**
   * The population stability index of {@code current} against {@code reference},
   * on ten bins cut at the reference's deciles. Under 0.1 is stable, 0.1 to 0.25
   * worth a look, over 0.25 a shift to act on. Empty below {@link #MIN_FOR_PSI}
   * values on either side.
   */
  public static Optional<Double> psi(List<Double> reference, List<Double> current) {
    if (reference.size() < MIN_FOR_PSI || current.size() < MIN_FOR_PSI) {
      return Optional.empty();
    }
    double[] sorted = reference.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    double[] cuts = new double[9];
    for (int i = 1; i <= 9; i++) {
      cuts[i - 1] = sorted[(int) Math.floor(i * sorted.length / 10.0)];
    }
    double[] ref = shares(reference, cuts);
    double[] cur = shares(current, cuts);
    double psi = 0;
    for (int i = 0; i < 10; i++) {
      psi += (cur[i] - ref[i]) * Math.log(cur[i] / ref[i]);
    }
    return Optional.of(psi);
  }

  private static double[] shares(List<Double> values, double[] cuts) {
    double[] counts = new double[10];
    for (double v : values) {
      int bin = 0;
      while (bin < cuts.length && v > cuts[bin]) {
        bin++;
      }
      counts[bin]++;
    }
    for (int i = 0; i < 10; i++) {
      // A bin with nothing in it would make the log infinite; a small floor keeps it finite.
      counts[i] = Math.max(counts[i] / values.size(), 1e-4);
    }
    return counts;
  }
}
