package com.waypoint.dispatch.execution.contract;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payloads of Execution's commands. All are safe to replay from an offline
 * device: the command id is the idempotency key, and the server's clock decides.
 */
public final class ExecutionCommands {
  private ExecutionCommands() {}

  public static final String START_STOP = "delivery:Start";
  public static final String RECORD_ARRIVAL = "delivery:RecordArrival";
  public static final String RECORD = "delivery:Record";
  public static final String CAPTURE_PROOF = "delivery:CaptureProof";
  public static final String REPORT_VEHICLE_STATUS = "delivery:ReportVehicleStatus";
  public static final String REPORT_FAULT = "delivery:ReportFault";
  /** The driver moves on before the store answered, with the reason (issue #21). */
  public static final String LEAVE_WITHOUT_STORE_ANSWER = "delivery:LeaveWithoutStoreAnswer";
  public static final String RECORD_POSITIONS = "delivery:RecordPositions";
  /** R-EXE-24: the driver is at the depot with the vehicle, ready for it to be loaded. */
  public static final String ARRIVE_AT_DEPOT = "delivery:ArriveAtDepot";

  public record StartStop(UUID deliveryId) {}

  public record ArriveAtDepot(String vehicleId) {}

  public record RecordArrival(UUID deliveryId, Optional<Instant> deviceArrivedAt) {}

  /**
   * @param outcome {@code DELIVERED}, {@code PARTIAL} or {@code FAILED}
   * @param dispositionNote what happened to undelivered goods; there is no
   *     returns workflow (A-10)
   * @param lines what arrived of each product, to record the delivery product
   *     by product; empty to record a total only. When the products add up to
   *     the order's unit count, {@code deliveredUnits} follows from them
   */
  public record RecordDelivery(
      UUID deliveryId,
      DeliveryOutcome outcome,
      Optional<Integer> deliveredUnits,
      Optional<String> reason,
      Optional<String> dispositionNote,
      List<DeliveredLine> lines) {

    public RecordDelivery {
      lines = List.copyOf(lines);
    }
  }

  public record DeliveredLine(String productId, int units) {}

  /**
   * A device limitation never blocks the work: with no photo or signature the
   * driver records why and continues (R-EXE-11).
   */
  public record CaptureProof(
      UUID deliveryId,
      Optional<UUID> photoAttachmentId,
      Optional<UUID> signatureAttachmentId,
      Optional<String> recipientName,
      Optional<String> fallbackReason) {}

  /** The driver's report of the vehicle's state; the dispatcher applies any status change. */
  public record ReportVehicleStatus(String vehicleId, String status, Optional<String> note) {}

  /** @param kind {@code vehicle} or {@code road} */
  public record ReportFault(
      String vehicleId, Optional<UUID> deliveryId, String kind, String description) {}

  /**
   * A batch of 1-100 GPS fixes from the assigned driver's phone, oldest first
   * (R-EXE-18). Append only, so no expectedVersion: the command id makes a
   * replay a no-op and a repeated fix is stored once.
   */
  public record RecordPositions(String vehicleId, Optional<UUID> tripId, List<PositionPoint> points) {
    public RecordPositions {
      points = List.copyOf(points);
    }
  }

  public record PositionPoint(
      Instant recordedAt,
      java.math.BigDecimal latitude,
      java.math.BigDecimal longitude,
      Optional<java.math.BigDecimal> accuracyM,
      Optional<java.math.BigDecimal> headingDeg,
      Optional<java.math.BigDecimal> speedKmh) {}
}
