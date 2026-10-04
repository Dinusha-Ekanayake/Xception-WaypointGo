package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderCommands;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only thing that releases the warehouse reservation (D-H, R-STK-14).
 *
 * <p>Ordering does not call the warehouse here: {@code order.cancelled} carries
 * the reservation reference and Warehouse cancels it from the event, so a
 * cancellation commits even while the warehouse is down and is compensated
 * when it returns (R-STK-09).
 */
@Component
public class CancelOrderHandler implements CommandHandler {
  private final JdbcOrderRepository orders;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public CancelOrderHandler(
      JdbcOrderRepository orders, EventPublisher events, Metrics metrics, Clock clock) {
    this.orders = orders;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return OrderCommands.CANCEL;
  }

  @Override
  public String action() {
    return OrderCommands.CANCEL;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ORDERING;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("orderId");
    return id == null ? null : "wpt:order:order:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = OrderMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID orderId = payload.uuid("orderId");
    String reason = payload.requiredText("reason").trim();
    if (reason.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A reason of at least three characters is required");
    }
    Instant now = clock.now();

    Order order = OrderMessages.current(orders, orderId, expected);
    Order cancelled;
    try {
      cancelled = order.cancel();
    } catch (DomainException e) {
      metrics.increment("waypoint.order.cancel_refused", "status", JdbcOrderRepository.code(order.status()));
      throw e;
    }
    long version = orders.update(cancelled, expected, false, now);
    orders.recordStatus(
        orderId, Optional.of(order.status()), cancelled.status(), "cancelled: " + reason,
        actor.userId(), Optional.empty(), now);
    events.publish(actor, OrderMessages.cancelled(order, reason));
    metrics.increment("waypoint.order.cancelled", "from", JdbcOrderRepository.code(order.status()));

    return Map.of("orderId", orderId.toString(), "status", cancelled.status().name(), "rowVersion", version);
  }
}
