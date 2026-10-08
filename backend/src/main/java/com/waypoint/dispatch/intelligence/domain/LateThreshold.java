package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import java.util.List;
import java.util.Optional;

/**
 * Freezing the threshold of a model's late flag from its backtest (roadmap
 * MF-04, T1-02, R-ML-22). On the labelled stops it predicted over the last
 * eight finished weeks, the lowest threshold of 0.30 to 0.90 in steps of 0.05
 * at which the 95% Wilson lower bound of the flag's precision is
 * {@link #PRECISION} or more on {@link LateNoticePolicy#MIN_FLAGGED} or more
 * flagged stops. Chosen once per model and never moved: a threshold that
 * followed the latest weeks would hide drift. With fewer than
 * {@link Backtest#MIN_LABELS} labelled stops, or no threshold that qualifies,
 * nothing is frozen and the provisional {@link LateNoticePolicy#THRESHOLD}
 * applies. Pure.
 */
public final class LateThreshold {
  public static final double PRECISION = 0.60;
  static final double LOWEST = 0.30;
  static final double HIGHEST = 0.90;
  static final double STEP = 0.05;

  private LateThreshold() {}

  public record Choice(double threshold, int flagged, int late, double precisionLower, int labelled) {}

  public static Optional<Choice> choose(List<Scored> labelled) {
    if (labelled.size() < Backtest.MIN_LABELS) {
      return Optional.empty();
    }
    for (int i = 0; LOWEST + i * STEP <= HIGHEST + 1e-9; i++) {
      double t = Math.round((LOWEST + i * STEP) * 100) / 100.0;
      int flagged = 0;
      int late = 0;
      for (Scored s : labelled) {
        if (s.probability() >= t) {
          flagged++;
          if (s.late()) {
            late++;
          }
        }
      }
      double lower = LateNoticePolicy.wilsonLower(late, flagged);
      if (flagged >= LateNoticePolicy.MIN_FLAGGED && lower >= PRECISION) {
        return Optional.of(new Choice(t, flagged, late, lower, labelled.size()));
      }
    }
    return Optional.empty();
  }
}
