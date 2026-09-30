package com.waypoint.dispatch.receipt.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Events Receipt publishes. Consumers: Ordering, Issues, Notification. */
public final class ReceiptEvents {
  private ReceiptEvents() {}

  public record ReceiptConfirmed(
      UUID receiptId, UUID orderId, String outletId, boolean partial, Instant confirmedAt)
      implements DomainEvent {
    public static final String TYPE = "receipt.confirmed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "receipt";
    }

    @Override
    public String aggregateId() {
      return receiptId.toString();
    }
  }

  public record ReceiptDisputed(
      UUID receiptId, UUID orderId, String outletId, String depotCode, String reason, Instant at)
      implements DomainEvent {
    public static final String TYPE = "receipt.disputed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "receipt";
    }

    @Override
    public String aggregateId() {
      return receiptId.toString();
    }
  }

  public record ReceiptAutoClosed(UUID receiptId, UUID orderId, String outletId, Instant at)
      implements DomainEvent {
    public static final String TYPE = "receipt.auto_closed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "receipt";
    }

    @Override
    public String aggregateId() {
      return receiptId.toString();
    }
  }
}
