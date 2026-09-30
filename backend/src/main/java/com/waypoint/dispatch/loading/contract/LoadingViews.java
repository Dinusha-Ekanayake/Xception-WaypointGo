package com.waypoint.dispatch.loading.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * What the loader and other modules see of dock work.
 *
 * <p>A manifest is tied to the plan version it was built from. When Planning
 * publishes a revision, the manifest is rebuilt and earlier checks no longer
 * count toward release (R-LOD-03, R-LOD-07): a stale list is never shown.
 */
public final class LoadingViews {
  private LoadingViews() {}

  public enum SessionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED
  }

  public enum CheckStatus {
    PENDING,
    LOADED,
    SHORT,
    MISSING,
    DAMAGED
  }

  /**
   * @param lines in loading order: the reverse of the stop sequence, so the
   *     last stop is loaded first (decision D-L)
   */
  public record ManifestView(
      UUID tripId,
      UUID planId,
      int planVersion,
      String vehicleId,
      int tripNumber,
      SessionStatus status,
      List<ManifestLineView> lines,
      long rowVersion) {

    public ManifestView {
      lines = List.copyOf(lines);
    }
  }

  /**
   * @param loadSequence 1 is loaded first
   * @param stopSequence 1 is delivered first
   * @param attempt increases on every recheck; a recheck never overwrites
   */
  public record ManifestLineView(
      int loadSequence,
      int stopSequence,
      UUID orderId,
      String outletId,
      String temperature,
      int itemCount,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      CheckStatus status,
      int loadedUnits,
      int attempt) {}

  public record ReadyTripView(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      LocalTime plannedDeparture,
      SessionStatus status) {}

  public record ShortfallView(
      UUID shortfallId,
      UUID tripId,
      UUID orderId,
      CheckStatus kind,
      int missingUnits,
      String reason,
      UUID reportedBy,
      Instant reportedAt) {}
}
