package com.waypoint.dispatch.receipt.contract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payloads of Receipt's commands. A shortage reported after auto-close is still accepted (RCP-08). */
public final class ReceiptCommands {
  private ReceiptCommands() {}

  public static final String CONFIRM = "receipt:Confirm";
  public static final String CONFIRM_PARTIAL = "receipt:ConfirmPartial";
  public static final String DISPUTE = "receipt:Dispute";

  public record ReceivedLine(String productId, int receivedQuantity) {}

  public record ConfirmReceipt(UUID orderId) {}

  public record ConfirmPartialReceipt(UUID orderId, List<ReceivedLine> lines, Optional<String> note) {

    public ConfirmPartialReceipt {
      lines = List.copyOf(lines);
    }
  }

  public record DisputeReceipt(UUID orderId, String reason, List<ReceivedLine> lines) {

    public DisputeReceipt {
      lines = List.copyOf(lines);
    }
  }
}
