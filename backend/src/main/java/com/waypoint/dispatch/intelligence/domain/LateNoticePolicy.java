package com.waypoint.dispatch.intelligence.domain;

import java.util.Optional;

/**
 * Who is told that a published stop will likely arrive after its window
 * (roadmap T1-02, R-ML-22). Pure.
 *
 * <ul>
 *   <li>Only a stop at or above the model's frozen threshold ({@link LateThreshold}),
 *       else the provisional {@link #THRESHOLD}, is told about, once.
 *   <li>The store is told only once the trust gate is open: over the last four
 *       weeks of labelled stops, the lower bound of the 95% Wilson interval of
 *       the flag's precision is {@link #STORE_PRECISION} or more, on at least
 *       {@link #MIN_FLAGGED} flagged stops. Until then the dispatcher is told,
 *       and may pass it on.
 *   <li>A store hears at most {@link #MAX_PER_WEEK} a week; beyond that the
 *       dispatcher is told instead.
 * </ul>
 */
public final class LateNoticePolicy {
  /** Provisional until the backtest freezes one (MF-04). */
  public static final double THRESHOLD = 0.5;
  public static final double STORE_PRECISION = 0.80;
  public static final int MIN_FLAGGED = 30;
  public static final int MAX_PER_WEEK = 2;
  public static final int GATE_DAYS = 28;
  private static final double Z = 1.96;

  private LateNoticePolicy() {}

  public enum Audience {
    DISPATCHER,
    STORE
  }

  public static Optional<Audience> decide(
      double lateProbability, boolean alreadyTold, int storeNoticesThisWeek, boolean storesOpen) {
    return decide(lateProbability, THRESHOLD, alreadyTold, storeNoticesThisWeek, storesOpen);
  }

  /** As above, at the threshold frozen for the model that predicted it (MF-04), when there is one. */
  public static Optional<Audience> decide(
      double lateProbability, double threshold, boolean alreadyTold, int storeNoticesThisWeek, boolean storesOpen) {
    if (alreadyTold || !(lateProbability >= threshold)) {
      return Optional.empty();
    }
    return Optional.of(storesOpen && storeNoticesThisWeek < MAX_PER_WEEK ? Audience.STORE : Audience.DISPATCHER);
  }

  /** The store gate: of {@code flagged} stops that were labelled, {@code late} really were. */
  public static boolean storesOpen(int late, int flagged) {
    return flagged >= MIN_FLAGGED && wilsonLower(late, flagged) >= STORE_PRECISION;
  }

  /** The lower bound of the 95% Wilson score interval of a proportion; 0 with no trials. */
  public static double wilsonLower(int successes, int trials) {
    if (trials <= 0) {
      return 0;
    }
    double p = (double) successes / trials;
    double z2 = Z * Z;
    double centre = p + z2 / (2.0 * trials);
    double margin = Z * Math.sqrt(p * (1 - p) / trials + z2 / (4.0 * trials * trials));
    return (centre - margin) / (1 + z2 / trials);
  }
}
