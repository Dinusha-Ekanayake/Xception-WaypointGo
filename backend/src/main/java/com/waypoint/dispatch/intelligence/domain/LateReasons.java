package com.waypoint.dispatch.intelligence.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Why a stop is at risk of being late, in words a dispatcher can act on
 * (roadmap MF-08, R-ML-15). Not the model's internals: the facts the estimate
 * leans on, each scored by how much it weighs, strongest first, at most three.
 * Pure.
 *
 * <ul>
 *   <li>{@code TIGHT_WINDOW}: little time between the planned arrival and the
 *       window closing (under {@link #TIGHT_MINUTES}).
 *   <li>{@code LATE_IN_ROUTE}: far down the trip, where delays have built up
 *       (from the {@link #LATE_STOP_INDEX}th stop).
 *   <li>{@code OUTLET_HISTORY}: the store was late on many of its recent
 *       labelled visits (at least {@link #MIN_VISITS}, and {@link #HISTORY_RATE}
 *       or more of them).
 * </ul>
 */
public final class LateReasons {
  public static final int TIGHT_MINUTES = 30;
  /** Zero-based: the fifth stop on. */
  public static final int LATE_STOP_INDEX = 4;
  public static final int MIN_VISITS = 3;
  public static final double HISTORY_RATE = 0.3;
  public static final int MAX = 3;

  private LateReasons() {}

  /**
   * @param code what the reason is; the screen words it
   * @param value its number: minutes of slack, the stop's number, or late visits
   * @param of the second number where one is needed: the labelled visits
   */
  public record Reason(String code, int value, int of) {}

  private record Weighed(Reason reason, double weight) {}

  /**
   * @param slackMinutes window close less planned arrival
   * @param stopIndex zero-based position on the trip
   * @param lateVisits labelled late visits of the outlet in the recent window
   * @param labelledVisits labelled visits of the outlet in the recent window
   */
  public static List<Reason> of(long slackMinutes, int stopIndex, int lateVisits, int labelledVisits) {
    List<Weighed> out = new ArrayList<>();
    if (slackMinutes < TIGHT_MINUTES) {
      double weight = 1 - Math.max(0, slackMinutes) / (double) TIGHT_MINUTES;
      out.add(new Weighed(new Reason("TIGHT_WINDOW", (int) Math.max(0, slackMinutes), 0), 0.5 + weight));
    }
    if (stopIndex >= LATE_STOP_INDEX) {
      out.add(new Weighed(new Reason("LATE_IN_ROUTE", stopIndex + 1, 0), Math.min(1, stopIndex / 8.0)));
    }
    if (labelledVisits >= MIN_VISITS && lateVisits / (double) labelledVisits >= HISTORY_RATE) {
      out.add(new Weighed(new Reason("OUTLET_HISTORY", lateVisits, labelledVisits), lateVisits / (double) labelledVisits));
    }
    return out.stream()
        .sorted(Comparator.comparingDouble(Weighed::weight).reversed())
        .limit(MAX)
        .map(Weighed::reason)
        .toList();
  }
}
