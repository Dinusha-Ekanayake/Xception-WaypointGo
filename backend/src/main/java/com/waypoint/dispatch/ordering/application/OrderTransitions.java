package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderStateMachine;
import com.waypoint.dispatch.ordering.domain.OrderStateMachine.EventOutcome;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How a consumed event moves an order: the one place Ordering applies another
 * module's news to its own status, so no module writes an order's status but
 * Ordering (see {@link OrderStatus}).
 *
 * <p>Runs inside the relay's transaction. Delivery is at least once and not in
 * order, so every event is judged by {@link OrderStateMachine#onEvent}: a legal
 * move applies, a late or repeated one is a no-op, and an impossible one is
 * counted and logged but never thrown, because throwing would block the
 * consumer on one bad event forever. Nothing here logs a payload.
 */
@Component
public class OrderTransitions {
  private static final Logger log = LoggerFactory.getLogger(OrderTransitions.class);

  private final JdbcOrderRepository orders;
  private final Metrics metrics;
  private final Clock clock;

  public OrderTransitions(JdbcOrderRepository orders, Metrics metrics, Clock clock) {
    this.orders = orders;
    this.metrics = metrics;
    this.clock = clock;
  }

  public enum Result {
    APPLIED,
    STALE,
    ILLEGAL,
    UNKNOWN_ORDER
  }

  /** Moves the order to {@code to}, as judged against where it is now. */
  public Result move(UUID orderId, OrderStatus to, String reason, EventEnvelope<?> envelope) {
    return apply(orderId, to, order -> order.moveTo(to), reason, envelope);
  }

  /**
   * @param change builds the next state from the current one; only called when
   *     the move is legal
   */
  public Result apply(
      UUID orderId,
      OrderStatus to,
      UnaryOperator<Order> change,
      String reason,
      EventEnvelope<?> envelope) {
    Optional<Order> found = orders.find(orderId);
    if (found.isEmpty()) {
      return unknown(orderId, envelope);
    }
    Order current = found.get();
    EventOutcome outcome = OrderStateMachine.onEvent(current.status(), to);
    if (outcome == EventOutcome.STALE) {
      metrics.increment("waypoint.order.event_stale", "event", envelope.type());
      return Result.STALE;
    }
    if (outcome == EventOutcome.ILLEGAL) {
      return illegal(current, to, envelope);
    }
    return write(current, change.apply(current), reason, envelope);
  }

  /** Writes a change already judged legal by the caller, such as a re-allocation to another trip. */
  public Result write(Order current, Order next, String reason, EventEnvelope<?> envelope) {
    orders.update(next, current.rowVersion(), false, clock.now());
    orders.recordStatus(
        current.orderId(),
        Optional.of(current.status()),
        next.status(),
        reason,
        envelope.actorId().orElse(null),
        Optional.of(envelope.eventId()),
        clock.now());
    metrics.increment(
        "waypoint.order.event_applied", "event", envelope.type(), "to", JdbcOrderRepository.code(next.status()));
    return Result.APPLIED;
  }

  public Result illegal(Order current, OrderStatus to, EventEnvelope<?> envelope) {
    metrics.increment("waypoint.order.event_illegal", "event", envelope.type());
    log.warn(
        "Ignored {} {} for order {}: {} cannot move to {}",
        envelope.type(), envelope.eventId(), current.orderId(), current.status(), to);
    return Result.ILLEGAL;
  }

  public Result unknown(UUID orderId, EventEnvelope<?> envelope) {
    metrics.increment("waypoint.order.event_unknown_order", "event", envelope.type());
    log.warn("Ignored {} {} for unknown order {}", envelope.type(), envelope.eventId(), orderId);
    return Result.UNKNOWN_ORDER;
  }
}
