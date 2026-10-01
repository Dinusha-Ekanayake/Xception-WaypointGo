package com.waypoint.dispatch.receipt.domain;

import com.waypoint.dispatch.receipt.contract.ReceiptCommands.ReceivedLine;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The store's acceptance of one delivered order (R-RCP-01, R-RCP-04).
 *
 * <p>A receipt exists only because a delivery was recorded: it is created from
 * {@code delivery.completed} and never from a store's request, so a store cannot
 * confirm a delivery that did not happen (RCP-04). The driver's proof is a
 * separate record in Execution; nothing here overwrites it.
 *
 * <p>Pure: every method takes the actor and the time as arguments.
 *
 * @param closesAt when silence becomes {@code AUTO_CLOSED}, stamped at creation (P-10)
 * @param late the store answered after the receipt auto-closed (RCP-08)
 * @param note the store's note on a partial receipt, or its reason for a dispute
 */
public record Receipt(
    UUID receiptId,
    UUID orderId,
    UUID deliveryId,
    UUID tripId,
    String outletId,
    String depotCode,
    ReceiptStatus status,
    List<ReceiptLine> lines,
    Optional<String> note,
    Optional<UUID> confirmedBy,
    Optional<Instant> confirmedAt,
    Instant deliveredAt,
    Instant closesAt,
    boolean late,
    long rowVersion) {

  public Receipt {
    Objects.requireNonNull(receiptId, "receiptId");
    Objects.requireNonNull(orderId, "orderId");
    if (deliveryId == null) {
      throw new DomainException(
          ErrorCode.CONFLICT, "a receipt needs a recorded delivery", List.of("RCP-04"));
    }
    lines = List.copyOf(lines);
  }

  /** The receipt a recorded delivery opens, waiting for the store. */
  public static Receipt pending(
      UUID receiptId,
      UUID orderId,
      UUID deliveryId,
      UUID tripId,
      String outletId,
      String depotCode,
      List<ReceiptLine> expected,
      Instant deliveredAt,
      AutoClosePolicy policy) {
    if (expected.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "a receipt needs the order's lines");
    }
    return new Receipt(
        receiptId, orderId, deliveryId, tripId, outletId, depotCode, ReceiptStatus.PENDING, expected,
        Optional.empty(), Optional.empty(), Optional.empty(), deliveredAt, policy.closesAt(deliveredAt),
        false, 1);
  }

  /** Everything expected arrived. Only while the receipt is still waiting. */
  public Receipt confirm(UUID actor, Instant at) {
    ReceiptStateMachine.require(status, ReceiptStatus.CONFIRMED);
    return answered(
        ReceiptStatus.CONFIRMED,
        lines.stream().map(ReceiptLine::receivedInFull).toList(),
        Optional.empty(), actor, at);
  }

  /**
   * Some of the order arrived. Lines not named arrived in full. At least one line
   * must be short, otherwise it is a confirmation; receiving more than expected is
   * a dispute, not a partial receipt.
   */
  public Receipt confirmPartial(List<ReceivedLine> received, Optional<String> note, UUID actor, Instant at) {
    ReceiptStateMachine.require(status, ReceiptStatus.PARTIAL);
    List<ReceiptLine> next = apply(received, false);
    if (next.stream().allMatch(l -> l.shortBy() == 0)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "no line is short; confirm the receipt instead of confirming it as partial",
          List.of("R-RCP-01"));
    }
    return answered(ReceiptStatus.PARTIAL, next, note.map(String::trim).filter(n -> !n.isEmpty()), actor, at);
  }

  /**
   * The store disagrees with what was delivered. Lines are optional: a dispute
   * may be about quantity, about condition, or about both. Over-delivery is a
   * legitimate dispute.
   */
  public Receipt dispute(String reason, List<ReceivedLine> received, UUID actor, Instant at) {
    ReceiptStateMachine.require(status, ReceiptStatus.DISPUTED);
    String why = reason == null ? "" : reason.trim();
    if (why.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "a dispute needs a reason of at least three characters", List.of("R-RCP-01"));
    }
    return answered(ReceiptStatus.DISPUTED, apply(received, true), Optional.of(why), actor, at);
  }

  /** Silence past the window: recorded as unconfirmed, never as received (R-RCP-05). */
  public Receipt autoClose(Instant now) {
    if (!isDue(now)) {
      throw new DomainException(
          ErrorCode.CONFLICT, "receipt " + receiptId + " is not due to auto-close", List.of("R-RCP-05"));
    }
    return new Receipt(
        receiptId, orderId, deliveryId, tripId, outletId, depotCode, ReceiptStatus.AUTO_CLOSED, lines,
        note, confirmedBy, confirmedAt, deliveredAt, closesAt, false, rowVersion);
  }

  public boolean isDue(Instant now) {
    return status == ReceiptStatus.PENDING && !now.isBefore(closesAt);
  }

  public int unitsShort() {
    return lines.stream().mapToInt(ReceiptLine::shortBy).sum();
  }

  // ---- internals -----------------------------------------------------------

  private Receipt answered(
      ReceiptStatus to, List<ReceiptLine> next, Optional<String> withNote, UUID actor, Instant at) {
    boolean afterClose = status == ReceiptStatus.AUTO_CLOSED;
    return new Receipt(
        receiptId, orderId, deliveryId, tripId, outletId, depotCode, to, next, withNote,
        Optional.of(actor), Optional.of(at), deliveredAt, closesAt, afterClose, rowVersion);
  }

  /** The received quantities applied to the expected lines; unnamed lines arrived in full. */
  private List<ReceiptLine> apply(List<ReceivedLine> received, boolean allowOver) {
    Map<String, ReceiptLine> byProduct = new LinkedHashMap<>();
    lines.forEach(l -> byProduct.put(l.productId(), l.receivedInFull()));
    Set<String> seen = new HashSet<>();
    for (ReceivedLine r : received) {
      ReceiptLine line = byProduct.get(r.productId());
      if (line == null) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "product " + r.productId() + " is not on this order", List.of("R-RCP-01"));
      }
      if (!seen.add(r.productId())) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "product " + r.productId() + " is named twice", List.of("R-RCP-01"));
      }
      if (!allowOver && r.receivedQuantity() > line.expectedQuantity()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "product " + r.productId() + " received " + r.receivedQuantity() + " of " + line.expectedQuantity()
                + "; receiving more than ordered is a dispute",
            List.of("R-RCP-01"));
      }
      byProduct.put(r.productId(), line.received(r.receivedQuantity()));
    }
    return new ArrayList<>(byProduct.values());
  }
}
