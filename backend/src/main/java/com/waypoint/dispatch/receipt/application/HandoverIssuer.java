package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.domain.Handover;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.infrastructure.JdbcHandoverRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Opens the handover PIN when the store answers a receipt (R-RCP-09).
 *
 * <p>Called inside the answer's transaction, so the PIN exists exactly when the
 * answer does. The vehicle and date the driver's scope is decided by come from
 * Execution's contract; if Execution cannot say, no PIN is issued and the answer
 * still stands, because the PIN is evidence and never a gate. That is counted, so
 * a silent run of receipts without handovers is a signal and not a surprise.
 */
@Component
public class HandoverIssuer {
  /** The PIN as the answer to the command that issued it, and when it stops working. */
  public record Issued(String pin, Instant expiresAt) {}

  private final JdbcHandoverRepository handovers;
  private final ObjectProvider<ExecutionQuery> execution;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();

  public HandoverIssuer(JdbcHandoverRepository handovers, ObjectProvider<ExecutionQuery> execution, Metrics metrics) {
    this.handovers = handovers;
    this.execution = execution;
    this.metrics = metrics;
  }

  public Optional<Issued> issue(Receipt receipt, UUID actor, Instant now) {
    ExecutionQuery query = execution.getIfAvailable();
    if (query == null) {
      return skipped("execution_unavailable");
    }
    Optional<DeliveryRecordView> delivery;
    try {
      delivery = query.deliveryForOrder(receipt.orderId());
    } catch (RuntimeException e) {
      return skipped("execution_error");
    }
    if (delivery.isEmpty()) {
      return skipped("no_delivery_record");
    }
    String pin = newPin();
    Handover handover =
        Handover.issue(
            receipt.receiptId(), receipt.orderId(), receipt.outletId(), receipt.depotCode(),
            delivery.get().vehicleId(), delivery.get().serviceDate(), pin, newSalt(), now);
    if (!handovers.insert(handover, now)) {
      return skipped("already_issued");
    }
    handovers.record(receipt.receiptId(), "issued", actor, now);
    metrics.increment("waypoint.receipt.handover_issued");
    return Optional.of(new Issued(pin, handover.expiresAt()));
  }

  /** A fresh salt, for an issue or a reissue. */
  String newSalt() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** A fresh PIN, for an issue or a reissue. */
  String newPin() {
    return Handover.newPin(random::nextInt);
  }

  private Optional<Issued> skipped(String reason) {
    metrics.increment("waypoint.receipt.handover_not_issued", "reason", reason);
    return Optional.empty();
  }
}
