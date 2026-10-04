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
      /** The units handed over, once recorded: a partial delivery is a stop total, not per line. */
      Optional<Integer> deliveredUnits,
      boolean proofCaptured,
      long rowVersion,
      List<DeliveryLineView> lines,
      /** Why the driver moved on before the store answered, when they did (issue #21): store_absent, no_signal, disagree. */
      Optional<String> storeAnswerWaived) {

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
   * @param stopSequence this stop's place on the trip
   * @param tripStopCount how many stops the trip has; empty on a record released before it was kept
   * @param plannedArrival the plan's time at this stop
   * @param expectedArrival the planned arrival shifted by the trip's delay, once one has been
   *     observed (R-EXE-15); empty until then, and the planned time is what the store prepares for
   * @param releasedAt when the trip left the dock
   * @param driver who drives the vehicle, filled in on the outlet and single-delivery reads only;
   *     empty when no driver is assigned for the date or Identity could not answer
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
      List<DeliveryLineView> lines,
      int stopSequence,
      Optional<Integer> tripStopCount,
      LocalTime plannedArrival,
      Optional<Instant> expectedArrival,
      Instant releasedAt,
      Optional<Instant> startedAt,
      Optional<DriverView> driver) {

    public DeliveryRecordView {
      lines = List.copyOf(lines);
    }

    /** The same record with the driver named; the driver is read from Identity, not from this record. */
    public DeliveryRecordView withDriver(Optional<DriverView> driver) {
      return new DeliveryRecordView(
          deliveryId, orderId, tripId, outletId, vehicleId, serviceDate, outcome, arrivedAt,
          serviceStartedAt, completedAt, waitMinutes, lateMinutes, lateReason, timingUncertain,
          deliveredUnits, failureReason, dispositionNote, lowEvidence, proofId, clientRecordedAt,
          serverRecordedAt, rowVersion, lines, stopSequence, tripStopCount, plannedArrival,
          expectedArrival, releasedAt, startedAt, driver);
    }
  }

  /**
   * Who is driving the vehicle, as a store manager may see it: a name and the
   * badge shown on screen, never an email or a phone number.
   */
  public record DriverView(String displayName, Optional<String> employeeCode) {}

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

  /**
   * What happened at one stop, as a model learns from it (issue #16). Waiting
   * for the window is its own figure, never part of service, so an early
   * arrival does not teach a long service time (EXE-18).
   *
   * @param serviceMinutes from service start to completion, when both were recorded
   */
  public record StopActualView(
      UUID deliveryId,
      UUID orderId,
      String outletId,
      String depotCode,
      String vehicleId,
      LocalDate serviceDate,
      int stopSequence,
      LocalTime plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<Instant> arrivedAt,
      Optional<Instant> serviceStartedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<java.math.BigDecimal> serviceMinutes,
      Optional<Integer> lateMinutes,
      DeliveryOutcome outcome,
      boolean timingUncertain) {}

  /**
   * A vehicle's last good fix (R-EXE-19). {@code offline} is true when its trip
   * is in progress and no good fix arrived for ten minutes; the position is then
   * where it was last seen, never an estimate.
   */
  public record VehiclePositionView(
      String vehicleId,
      Optional<UUID> tripId,
      java.math.BigDecimal latitude,
      java.math.BigDecimal longitude,
      Optional<java.math.BigDecimal> headingDeg,
      Optional<java.math.BigDecimal> accuracyM,
      Instant recordedAt,
      boolean offline) {}

  /** One point of a trip's recorded trail, oldest first. */
  public record TrailPointView(
      Instant recordedAt, java.math.BigDecimal latitude, java.math.BigDecimal longitude, boolean lowQuality) {}
}
