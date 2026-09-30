package com.waypoint.dispatch.execution.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the driver and other modules see of stops on the road.
 *
 * <p>Execution owns actual times; Planning keeps planned times only (decision
 * B18). Every record keeps both the device's clock and the server's, and the
 * server's decides (R-EXE-10).
 */
public final class ExecutionViews {
  private ExecutionViews() {}

  public enum DeliveryOutcome {
    PENDING,
    ARRIVED,
    DELIVERED,
    PARTIAL,
    FAILED,
    SKIPPED
  }

  public record RunSheetView(String vehicleId, LocalDate serviceDate, List<RunSheetStopView> stops) {

    public RunSheetView {
      stops = List.copyOf(stops);
    }
  }

  public record RunSheetStopView(
      UUID deliveryId,
      UUID tripId,
      int sequence,
      UUID orderId,
      String outletId,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<Instant> arrivedAt,
      Optional<Instant> completedAt,
      DeliveryOutcome outcome,
      long rowVersion) {}

  /**
   * @param waitMinutes time spent waiting for an early window to open, kept
   *     apart from service time (R-EXE-04)
   * @param lateMinutes measured against the window close, not the plan (R-EXE-14)
   * @param clientRecordedAt the device's clock, for forensics only
   * @param serverRecordedAt the server's clock, which decides
   */
  public record DeliveryRecordView(
      UUID deliveryId,
      UUID orderId,
      UUID tripId,
      String outletId,
      DeliveryOutcome outcome,
      Optional<Instant> arrivedAt,
      Optional<Instant> serviceStartedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<Integer> lateMinutes,
      Optional<Integer> deliveredUnits,
      Optional<String> failureReason,
      Optional<UUID> proofId,
      Optional<Instant> clientRecordedAt,
      Instant serverRecordedAt,
      long rowVersion) {}
}
