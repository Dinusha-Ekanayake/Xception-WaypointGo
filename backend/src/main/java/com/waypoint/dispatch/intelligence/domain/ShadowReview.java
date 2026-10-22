package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Issue #12: the shadow against the model that answered, on the same labelled
 * stops (R-ML-40), and when the system puts a replaced model back (R-ML-42).
 *
 * <p>The verdict is the one R-ML-16 and the retrain's replay use: better only
 * when both the Brier score and the log loss are lower, on at least
 * {@link #MIN_STOPS} stops with both classes among them. Pure.
 */
public final class ShadowReview {
  /** Labelled stops both models scored, fewer than this and nothing is decided. */
  public static final int MIN_STOPS = 200;
  /** Service days a review looks back over, from the day before it runs. */
  public static final int WINDOW_DAYS = 14;
  /** A promotion is rolled back only this long after it: later, a worse model is a person's to retire. */
  public static final Duration ROLLBACK_WINDOW = Duration.ofDays(28);

  private ShadowReview() {}

  public enum Verdict {
    BETTER,
    WORSE,
    MIXED,
    NOT_ENOUGH
  }

  /** One labelled stop: what the shadow and the answering model gave, and whether it was late. */
  public record Pair(double shadow, double incumbent, boolean late) {}

  public record Result(
      int stops,
      Optional<Double> shadowBrier,
      Optional<Double> shadowLogLoss,
      Optional<Double> incumbentBrier,
      Optional<Double> incumbentLogLoss,
      Verdict verdict) {}

  /** The verdict is the shadow's: {@code BETTER} means the shadow did better than the model that answered. */
  public static Result judge(List<Pair> pairs) {
    List<Scored> shadow = new ArrayList<>(pairs.size());
    List<Scored> incumbent = new ArrayList<>(pairs.size());
    long late = 0;
    for (Pair p : pairs) {
      shadow.add(new Scored(p.shadow(), p.late()));
      incumbent.add(new Scored(p.incumbent(), p.late()));
      late += p.late() ? 1 : 0;
    }
    Optional<Double> sb = ModelMetrics.brier(shadow);
    Optional<Double> sl = ModelMetrics.logLoss(shadow);
    Optional<Double> ib = ModelMetrics.brier(incumbent);
    Optional<Double> il = ModelMetrics.logLoss(incumbent);
    Verdict verdict;
    if (pairs.size() < MIN_STOPS || late == 0 || late == pairs.size()) {
      verdict = Verdict.NOT_ENOUGH;
    } else {
      boolean brier = sb.get() < ib.get();
      boolean loss = sl.get() < il.get();
      boolean brierWorse = sb.get() > ib.get();
      boolean lossWorse = sl.get() > il.get();
      verdict = brier && loss ? Verdict.BETTER : brierWorse && lossWorse ? Verdict.WORSE : Verdict.MIXED;
    }
    return new Result(pairs.size(), sb, sl, ib, il, verdict);
  }

  /** The first service day a review reads: the window's start, or the day the model became shadow if later. */
  public static LocalDate windowFrom(LocalDate today, LocalDate shadowedOn) {
    LocalDate start = today.minusDays(WINDOW_DAYS);
    return shadowedOn.isAfter(start) ? shadowedOn : start;
  }

  /**
   * R-ML-42: put the replaced model back when it did better than the model that
   * replaced it, on both scores, within {@link #ROLLBACK_WINDOW} of the promotion.
   * A candidate is never activated by the system, and a mixed or thin review never acts.
   */
  public static boolean rollBack(ShadowRole role, Verdict verdict, Optional<Instant> activeSince, Instant now) {
    return role == ShadowRole.REPLACED
        && verdict == Verdict.BETTER
        && activeSince.map(since -> !now.isAfter(since.plus(ROLLBACK_WINDOW))).orElse(false);
  }
}
