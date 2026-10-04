package com.waypoint.dispatch.receipt.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.domain.ReceiptLine;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What Receipt takes from other modules. One consumer: a recorded delivery opens
 * the receipt the store will answer. Receipt does not consume
 * {@code delivery.failed}: nothing arrived, so there is nothing to accept, and
 * Issues raises the failure (issue #13, decision 10).
 */
final class ReceiptConsumers {
  private static final Logger log = LoggerFactory.getLogger(ReceiptConsumers.class);

  private ReceiptConsumers() {}

  /** Runs as {@code waypoint_receipt} for the system actor, which row-level security admits. */
  abstract static class ReceiptConsumer<E extends com.waypoint.dispatch.shared.event.DomainEvent>
      implements EventSubscriber<E> {
    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.RECEIPT;
    }
  }

  /**
   * Opens a PENDING receipt with the order's lines, the depot from Ordering and
   * the auto-close deadline in force today (P-10). A redelivered event finds the
   * receipt already there and does nothing.
   */
  @Component
  static class OnDeliveryCompleted extends ReceiptConsumer<DeliveryCompleted> {
    private final JdbcReceiptRepository receipts;
    private final OrderQuery orders;
    private final Metrics metrics;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    OnDeliveryCompleted(JdbcReceiptRepository receipts, OrderQuery orders, Metrics metrics, Clock clock) {
      this.receipts = receipts;
      this.orders = orders;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "receipt.on-delivery-completed";
    }

    @Override
    public Class<DeliveryCompleted> eventType() {
      return DeliveryCompleted.class;
    }

    @Override
    public void on(EventEnvelope<DeliveryCompleted> envelope) {
      DeliveryCompleted e = envelope.payload();
      if (receipts.findByDelivery(e.deliveryId()).isPresent() || receipts.findByOrder(e.orderId()).isPresent()) {
        metrics.increment("waypoint.receipt.event_stale");
        return;
      }
      Optional<OrderView> order = orders.order(e.orderId());
      if (order.isEmpty()) {
        metrics.increment("waypoint.receipt.unknown_order");
        log.warn("delivery {} names order {}, which Ordering does not know", e.deliveryId(), e.orderId());
        return;
      }
      Instant now = clock.now();
      List<ReceiptLine> lines =
          order.get().lines().stream().map(l -> ReceiptLine.expected(l.productId(), l.quantity())).toList();
      Receipt receipt =
          Receipt.pending(
              UuidV7.generate(now, random),
              e.orderId(),
              e.deliveryId(),
              e.tripId(),
              e.outletId(),
              order.get().depotCode(),
              lines,
              e.completedAt(),
              receipts.parameters(e.completedAt().atZone(Clock.OPERATING_ZONE).toLocalDate()).autoClose());
      if (!receipts.insert(receipt, envelope.actorId(), e.deliveredUnits(), now)) {
        metrics.increment("waypoint.receipt.event_stale");
        return;
      }
      receipts.recordStatus(
          receipt.receiptId(), Optional.empty(), ReceiptStatus.PENDING, "delivery recorded",
          envelope.actorId().orElse(Actor.SYSTEM_ID), Optional.of(envelope.eventId()), now);
      metrics.increment("waypoint.receipt.opened");
    }
  }
}
