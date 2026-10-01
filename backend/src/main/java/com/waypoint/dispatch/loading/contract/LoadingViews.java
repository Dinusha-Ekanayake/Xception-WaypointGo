package com.waypoint.dispatch.loading.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the loader and other modules see of dock work.
 *
 * <p>A manifest is tied to the plan version it was built from. When Planning
 * publishes a revision, the manifest is rebuilt and earlier checks no longer
 * count toward release (R-LOD-03, R-LOD-07): a stale list is never shown.
 *
 * <p>An order is loaded item line by item line. An item line is one product and
 * its unit count, copied from the order. The order's own weight and volume stay
 * authoritative for capacity (R-ORD-12); lines are never summed for it.
 */
public final class LoadingViews {
  private LoadingViews() {}

  /**
   * NOT_STARTED nobody has taken the trip; IN_PROGRESS items are still to load;
   * BLOCKED an item is flagged and items are still to load; READY every item is
   * loaded or flagged and the trip waits for release; COMPLETED released.
   */
  public enum SessionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    BLOCKED,
    READY,
    COMPLETED
  }

  /**
   * PENDING not checked yet, LOADED on the vehicle. SHORT, MISSING, DAMAGED and
   * DOES_NOT_FIT are the issue choices of Figma 03: the affected units are not
   * loaded, and the dispatcher and store are told (R-LOD-02).
   */
  public enum CheckStatus {
    PENDING,
    LOADED,
    SHORT,
    MISSING,
    DAMAGED,
    DOES_NOT_FIT
  }

  /** The loader a trip is locked to, one at a time until release or hand back (R-LOD-11). */
  public record HolderView(UUID userId, String name, Optional<String> employeeCode, Instant since) {}

  /**
   * @param attempt increases on every recheck or undo; a recheck never overwrites
   * @param checkedBy the loader of the latest attempt, absent while unchecked
   */
  public record ItemView(
      int lineNo,
      String productId,
      int units,
      CheckStatus status,
      int loadedUnits,
      int attempt,
      Optional<Instant> checkedAt,
      Optional<UUID> checkedBy) {}

  /**
   * One order on the trip.
   *
   * @param loadSequence 1 is loaded first
   * @param stopSequence 1 is delivered first
   * @param status PENDING while any item is unchecked, LOADED when every item is
   *     loaded, otherwise the first flag
   * @param loadedUnits units of this order on the vehicle
   * @param attempt the highest attempt across its items
   */
  public record ManifestLineView(
      int loadSequence,
      int stopSequence,
      UUID orderId,
      String orderRef,
      String outletId,
      String districtName,
      Optional<LocalTime> windowOpen,
      Optional<LocalTime> windowClose,
      Optional<LocalTime> plannedArrival,
      String temperature,
      int itemCount,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      CheckStatus status,
      int loadedUnits,
      int attempt,
      List<ItemView> items) {

    public ManifestLineView {
      items = List.copyOf(items);
    }
  }

  /**
   * @param lines in loading order: the reverse of the stop sequence, so the last
   *     stop is loaded first (decision D-L)
   * @param rowVersion moves on by exactly one per accepted command and per plan
   *     revision; send it as expectedVersion
   */
  public record ManifestView(
      UUID tripId,
      UUID planId,
      int planVersion,
      String depotCode,
      LocalDate serviceDate,
      String vehicleId,
      int tripNumber,
      int tripsForVehicle,
      String brandCode,
      String districtName,
      String temperature,
      LocalTime plannedDeparture,
      String dockCode,
      BigDecimal weightCapKg,
      BigDecimal volumeCapM3,
      SessionStatus status,
      Optional<HolderView> holder,
      Optional<Instant> releasedAt,
      List<ManifestLineView> lines,
      long rowVersion) {

    public ManifestView {
      lines = List.copyOf(lines);
    }
  }

  public record ReadyTripView(
      UUID tripId,
      String vehicleId,
      int tripNumber,
      int tripsForVehicle,
      LocalTime plannedDeparture,
      SessionStatus status,
      String brandCode,
      String districtName,
      String temperature,
      String dockCode,
      int stopCount,
      int orderCount,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      Optional<HolderView> holder,
      Optional<Instant> releasedAt,
      long rowVersion) {}

  public record ShortfallView(
      UUID shortfallId,
      UUID tripId,
      UUID orderId,
      Optional<Integer> lineNo,
      CheckStatus kind,
      int missingUnits,
      String reason,
      UUID reportedBy,
      Instant reportedAt,
      Optional<Instant> resolvedAt) {}
}
