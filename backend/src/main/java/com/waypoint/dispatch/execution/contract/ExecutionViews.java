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

  /**
   * @param mallOutlet the outlet takes goods only inside its window; a late
   *     arrival there is recorded as failed, not delivered (R-PLN-14)
   * @param expectedArrival the planned arrival shifted by the trip's delay, once
   *     one has been observed (R-EXE-15)
   * @param proofCaptured false on a finished stop means proof is still owed
   */
  public record RunSheetStopView(
      UUID deliveryId,
      UUID tripId,
      int sequence,
      UUID orderId,
      String outletId,
      int itemCount,
      boolean mallOutlet,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<Instant> expectedArrival,
      Optional<Instant> startedAt,
      Optional<Instant> arrivedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<Integer> lateMinutes,
      DeliveryOutcome outcome,
      boolean proofCaptured,
      long rowVersion,
      List<DeliveryLineView> lines) {

    public RunSheetStopView {
      lines = List.copyOf(lines);
    }
  }

  /**
   * One product of the order and what arrived of it. The product id is the
   * warehouse's inferred candidate, never a verified SKU.
   *
   * @param deliveredUnits empty until the delivery is recorded product by product
   */
  public record DeliveryLineView(String productId, int orderedUnits, Optional<Integer> deliveredUnits) {}

  /**
   * @param waitMinutes time spent waiting for an early window to open, kept
   *     apart from service time (R-EXE-04)
   * @param lateMinutes measured against the window close, not the plan (R-EXE-14)
   * @param lateReason why a late stop was late (R-EXE-05)
   * @param timingUncertain the device and the server disagreed about when this
   *     happened, usually because it was recorded offline and replayed later
   * @param lowEvidence completed with neither photo nor signature (R-EXE-11)
   * @param clientRecordedAt the device's clock, for forensics only
   * @param serverRecordedAt the server's clock, which decides
   */
  public record DeliveryRecordView(
      UUID deliveryId,
      UUID orderId,
      UUID tripId,
      String outletId,
      String vehicleId,
      LocalDate serviceDate,
      DeliveryOutcome outcome,
      Optional<Instant> arrivedAt,
      Optional<Instant> serviceStartedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<Integer> lateMinutes,
      Optional<String> lateReason,
      boolean timingUncertain,
      Optional<Integer> deliveredUnits,
      Optional<String> failureReason,
      Optional<String> dispositionNote,
      boolean lowEvidence,
      Optional<UUID> proofId,
      Optional<Instant> clientRecordedAt,
      Instant serverRecordedAt,
      long rowVersion,
      List<DeliveryLineView> lines) {

    public DeliveryRecordView {
      lines = List.copyOf(lines);
    }
  }

  /**
   * The evidence for one delivery. The links are signed and short-lived: they
   * open these artifacts until {@code linksExpireAt} and nothing else.
   *
   * @param photoPending the proof names a photo that has not reached the server
   *     yet; the device uploads it when it has signal (EXE-10)
   */
  public record ProofView(
      UUID proofId,
      UUID deliveryId,
      Optional<String> recipientName,
      Optional<String> fallbackReason,
      boolean lowEvidence,
      Instant capturedAt,
      Optional<String> photoUrl,
      boolean photoPending,
      Optional<String> signatureUrl,
      boolean signaturePending,
      Instant linksExpireAt) {}
}
