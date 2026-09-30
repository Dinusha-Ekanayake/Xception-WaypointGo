package com.waypoint.dispatch.receipt.contract;

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
      long rowVersion) {

    public ReceiptView {
      lines = List.copyOf(lines);
    }
  }

  /** Per product, because orders carry descriptive lines (decision D-E). */
  public record ReceiptLineView(String productId, int expectedQuantity, Optional<Integer> receivedQuantity) {}

  public record PendingReceiptView(UUID orderId, UUID deliveryId, String outletId, Instant deliveredAt) {}
}
