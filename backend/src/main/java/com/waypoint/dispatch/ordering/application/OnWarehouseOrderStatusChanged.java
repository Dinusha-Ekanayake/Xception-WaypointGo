package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The warehouse answering late for an order saved while it was unreachable (D-G).
 *
 * <p>A reservation makes a {@code STOCK_UNKNOWN} order {@code CONFIRMED}; one
 * the cutoff already deferred stays deferred and becomes demand for its new
 * date. {@code insufficient} cancels it, since nothing was reserved and D-F
 * never holds a retried order waiting for stock. {@code expired} cancels a
 * partially reserved order the store never accepted. Other warehouse statuses are the
 * warehouse's own lifecycle and do not move a Waypoint order; a cancellation
 * made outside Waypoint (STK-11) is counted for the reconciler to raise.
 */
@Component
public class OnWarehouseOrderStatusChanged extends OrderingConsumer<WarehouseOrderStatusChanged> {
  private final JdbcOrderRepository orders;
  private final EventPublisher events;
  private final Metrics metrics;

  public OnWarehouseOrderStatusChanged(
      OrderTransitions transitions,
      JdbcOrderRepository orders,
      EventPublisher events,
      Metrics metrics) {
    super(transitions);
    this.orders = orders;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String consumerName() {
    return "ordering.on-warehouse-order-status-changed";
  }

  @Override
  public Class<WarehouseOrderStatusChanged> eventType() {
    return WarehouseOrderStatusChanged.class;
  }

  @Override
  public void on(EventEnvelope<WarehouseOrderStatusChanged> envelope) {
    WarehouseOrderStatusChanged e = envelope.payload();
    Optional<Order> found = orders.find(e.orderId());
    if (found.isEmpty()) {
      // STK-06: a reservation Waypoint does not know is never auto-applied.
      transitions.unknown(e.orderId(), envelope);
      return;
    }
    Order current = found.get();
    boolean awaiting = current.reservation().isEmpty()
        && (current.status() == OrderStatus.STOCK_UNKNOWN || current.status() == OrderStatus.DEFERRED);

    if (e.reservation().isPresent()) {
      if (!awaiting) {
        metrics.increment("waypoint.order.event_stale", "event", envelope.type());
        return;
      }
      Optional<Reservation> reservation = OrderMessages.reservation(e.reservation().get());
      if (reservation.isEmpty()) {
        metrics.increment("waypoint.order.reservation_unusable");
        return;
      }
      Order next = current.reserved(reservation.get());
      transitions.write(current, next, "warehouse reserved " + reservation.get().warehouseOrderRef(), envelope);
      events.publish(Actor.SYSTEM, OrderMessages.amended(next));
      return;
    }

    if ("insufficient".equals(e.status())) {
      if (!awaiting) {
        metrics.increment("waypoint.order.event_stale", "event", envelope.type());
        return;
      }
      transitions.write(current, current.moveTo(OrderStatus.CANCELLED), "insufficient_stock", envelope);
      events.publish(Actor.SYSTEM, OrderMessages.cancelled(current, "insufficient_stock"));
      return;
    }

    if ("expired".equals(e.status())) {
      if (current.status() != OrderStatus.PARTIALLY_RESERVED) {
        metrics.increment("waypoint.order.event_stale", "event", envelope.type());
        return;
      }
      // The store never accepted the shortfall and the lock ran out.
      transitions.write(current, current.moveTo(OrderStatus.CANCELLED), "reservation_expired", envelope);
      events.publish(Actor.SYSTEM, OrderMessages.cancelled(current, "reservation_expired"));
      return;
    }

    if ("cancelled".equals(e.status()) && current.status() != OrderStatus.CANCELLED) {
      metrics.increment("waypoint.order.cancelled_outside_waypoint");
    }
  }
}
