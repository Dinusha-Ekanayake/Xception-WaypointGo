package com.waypoint.dispatch.intelligence.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Roadmap T1-01: the late-risk review of a draft before it is published. */
public final class RiskReviewViews {
  public static final String RECORD = "ml:RecordRiskReview";

  private RiskReviewViews() {}

  /** The dispatcher's latest choice for a stop, with who, when and why. */
  public record ReviewView(UUID reviewId, UUID planId, UUID orderId, String choice, String reason, UUID decidedBy,
      Instant decidedAt) {}

  /**
   * @param minutesToClose planned this many minutes before the window closes; negative after
   * @param stopOfTrip its place on the trip, 1 first
   */
  public record RiskStopView(UUID orderId, UUID tripId, String vehicleId, int sequence, String outletId, int percent,
      long minutesToClose, int stopOfTrip, Optional<ReviewView> review) {}

  /**
   * @param enabled false when the review is switched off here; the screen then shows nothing
   * @param estimate true while the figures are the deterministic estimate (trust gates not passed)
   */
  public record RiskReviewReport(UUID planId, boolean enabled, boolean estimate, int thresholdPercent,
      List<RiskStopView> stops) {
    public RiskReviewReport {
      stops = List.copyOf(stops);
    }
  }
}
