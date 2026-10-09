package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Roadmap T1-01: before a plan is published, the stops likely to arrive after
 * their window, for the dispatcher to keep or swap, each choice with a reason.
 * Advice only: nothing here changes the plan (R-ML-05). The chance is the
 * deterministic estimate tuned on Waypoint's own outcomes (R-ML-14), labelled
 * an estimate on screen until the trust gates pass.
 */
public final class RiskReview {
  /** From this chance a stop is listed: the plan board's red (Figma 2, "over 35% chance"). */
  public static final int THRESHOLD_PERCENT = 35;

  private RiskReview() {}

  public enum Choice { KEEP, SWAP }

  /** One stop as planned, in trip order. */
  public record PlannedStop(
      UUID orderId, UUID tripId, String vehicleId, int sequence, int indexInTrip, String outletId,
      LocalTime plannedArrival, LocalTime windowClose) {}

  /**
   * @param minutesToClose how long before its window closes the stop is planned; negative after
   * @param stopOfTrip its place on the trip, 1 first
   */
  public record Flagged(
      UUID orderId, UUID tripId, String vehicleId, int sequence, String outletId, int percent,
      long minutesToClose, int stopOfTrip) {}

  /** The stops at or above the threshold, likeliest first. */
  public static List<Flagged> flag(List<PlannedStop> stops, FallbackTuning.LateTuning tuning) {
    List<Flagged> out = new ArrayList<>();
    for (PlannedStop s : stops) {
      BigDecimal p = DeterministicEstimator.lateProbability(s.plannedArrival(), s.windowClose(), s.indexInTrip(), tuning, s.outletId());
      int percent = p.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
      if (percent >= THRESHOLD_PERCENT) {
        out.add(new Flagged(s.orderId(), s.tripId(), s.vehicleId(), s.sequence(), s.outletId(), percent,
            Duration.between(s.plannedArrival(), s.windowClose()).toMinutes(), s.indexInTrip() + 1));
      }
    }
    out.sort(Comparator.comparingInt(Flagged::percent).reversed().thenComparing(Flagged::vehicleId).thenComparingInt(Flagged::sequence));
    return out;
  }

  public static Choice choice(String text) {
    try {
      return Choice.valueOf(text == null ? "" : text.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "choice must be KEEP or SWAP");
    }
  }

  /** A review carries why (rule 8): 3 to 500 characters. */
  public static String reason(String text) {
    String r = text == null ? "" : text.strip();
    if (r.length() < 3 || r.length() > 500) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give a reason of 3 to 500 characters");
    }
    return r;
  }
}
