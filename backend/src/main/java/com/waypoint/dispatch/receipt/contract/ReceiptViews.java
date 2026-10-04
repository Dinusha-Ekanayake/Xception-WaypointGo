package com.waypoint.dispatch.receipt.contract;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the store and other modules see of a receipt.
 *
 * <p>Acceptance is a separate record from the driver's proof (R-RCP-04): proof
 * says goods were handed over, a receipt says the store agrees what arrived.
 * The receipt model may be refined additively by the module owner (decision D-O).
 */
public final class ReceiptViews {
  private ReceiptViews() {}

  public enum ReceiptStatus {
    PENDING,
    CONFIRMED,
    PARTIAL,
    DISPUTED,
    /** Not confirmed within the window; closed by the system actor (R-RCP-05). */
    AUTO_CLOSED
  }

  /**
   * @param tripId the trip that carried the order, linking the receipt to the
   *     loading check (R-RCP-08)
   * @param autoClosesAt when silence becomes {@code AUTO_CLOSED} (R-RCP-05)
   * @param late the store answered after auto-close; still accepted (RCP-08)
   */
  public record ReceiptView(
      UUID receiptId,
      UUID orderId,
      UUID deliveryId,
      String outletId,
      ReceiptStatus status,
      List<ReceiptLineView> lines,
      Optional<String> note,
      Optional<UUID> confirmedBy,
      Optional<Instant> confirmedAt,
      long rowVersion,
      Optional<UUID> tripId,
      String depotCode,
      Instant deliveredAt,
      Instant autoClosesAt,
      boolean late) {

    public ReceiptView {
      lines = List.copyOf(lines);
    }
  }

  /** Per product, because orders carry descriptive lines (decision D-E). */
  public record ReceiptLineView(String productId, int expectedQuantity, Optional<Integer> receivedQuantity) {}

  public record PendingReceiptView(UUID orderId, UUID deliveryId, String outletId, Instant deliveredAt) {}

  /**
   * The custody chain for one order, side by side: who checked it at the dock,
   * who delivered it, who received it (R-RCP-08). Each record stands as its
   * module wrote it; nothing here amends one to agree with another (R-RCP-07).
   *
   * <p>A neighbour whose module is not deployed yet is named in
   * {@code unavailable} rather than shown as empty, so missing evidence is never
   * mistaken for absent evidence (rule 9).
   *
   * @param delivery what Receipt recorded from {@code delivery.completed}
   * @param loadingCheck the order's line on the trip's manifest, from Loading
   * @param proof the delivery record, from Execution
   */
  public record CustodyChainView(
      UUID orderId,
      ReceiptView receipt,
      DeliveryFacts delivery,
      Optional<ManifestLineView> loadingCheck,
      Optional<DeliveryRecordView> proof,
      List<String> unavailable) {

    public CustodyChainView {
      unavailable = List.copyOf(unavailable);
    }
  }

  /** @param recordedBy the actor who recorded the delivery, when the event named one */
  public record DeliveryFacts(
      UUID deliveryId,
      Optional<UUID> tripId,
      Instant completedAt,
      Optional<Integer> deliveredUnits,
      Optional<UUID> recordedBy) {}

  /** Past its time reads as {@code EXPIRED} though the row still says awaiting. */
  public enum HandoverStatus {
    AWAITING,
    CONFIRMED,
    LOCKED,
    EXPIRED
  }

  /**
   * Where the handover PIN stands, for the store's screen. The PIN itself is
   * never here: it is returned once, by the command that issued it (R-RCP-09).
   *
   * @param attemptsLeft wrong entries the driver may still make
   * @param rowVersion the version a reissue is made against
   */
  /**
   * The store's answer as the driver sees it (issue #21, store-led handover): what
   * the store counted per product and its note, and where the PIN stands. Only the
   * driver of the vehicle on that date reads it, and only once the store answered.
   *
   * @param answeredAt when the store answered; the receipt's own confirmation time
   */
  public record ReceiptAnswerView(
      UUID orderId,
      ReceiptStatus status,
      List<ReceiptLineView> lines,
      Optional<String> note,
      Optional<Instant> answeredAt,
      HandoverView handover) {

    public ReceiptAnswerView {
      lines = List.copyOf(lines);
    }
  }

  public record HandoverView(
      UUID orderId,
      HandoverStatus status,
      Instant expiresAt,
      int attemptsLeft,
      Optional<Instant> confirmedAt,
      long rowVersion) {}
}
