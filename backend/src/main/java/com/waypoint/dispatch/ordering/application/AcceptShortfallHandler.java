package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderCommands;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
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
import com.waypoint.dispatch.warehouse.contract.StockPort;
import com.waypoint.dispatch.warehouse.contract.StockPort.ConfirmResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Confirmed;
import com.waypoint.dispatch.warehouse.contract.StockPort.Expired;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The store accepting a partial reservation: the warehouse confirms the locked
 * quantities and they become the order (D-F revised 2026-10-01).
 *
 * <p>The warehouse's confirmed totals replace the order's, and the lines become
 * the locked quantities as a new revision, so what the store first asked for is
 * kept in the history. If the lock already ran out the order is cancelled here,
 * in the same transaction, with the reason recorded: there is nothing left to
 * accept. An unreachable warehouse leaves the order unchanged.
 */
@Component
public class AcceptShortfallHandler implements CommandHandler {
  private final JdbcOrderRepository orders;
  private final StockPort stock;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public AcceptShortfallHandler(
      JdbcOrderRepository orders, StockPort stock, EventPublisher events, Metrics metrics, Clock clock) {
    this.orders = orders;
    this.stock = stock;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return OrderCommands.ACCEPT_SHORTFALL;
  }

  @Override
  public String action() {
    return OrderCommands.ACCEPT_SHORTFALL;
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
    UUID orderId = CommandPayload.of(command).uuid("orderId");
    Instant now = clock.now();

    Order order = OrderMessages.current(orders, orderId, expected);
    if (order.status() != com.waypoint.dispatch.ordering.contract.OrderStatus.PARTIALLY_RESERVED) {
      throw new DomainException(
          ErrorCode.CONFLICT, "Order " + order.orderRef() + " is " + order.status() + ", not partially reserved");
    }
    String ref = order.reservation().orElseThrow().warehouseOrderRef();
    ConfirmResult result = stock.confirmReservation(ref);

    if (result instanceof Unavailable u) {
      metrics.increment("waypoint.order.shortfall", "result", "unavailable");
      throw new DomainException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          "The warehouse could not confirm order " + order.orderRef() + "; it is unchanged: " + u.reason());
    }
    if (result instanceof Expired e) {
      Order cancelled = order.cancel();
      long version = orders.update(cancelled, expected, false, now);
      orders.recordStatus(orderId, Optional.of(order.status()), cancelled.status(),
          "reservation_expired: " + e.reason(), actor.userId(), Optional.empty(), now);
      events.publish(actor, OrderMessages.cancelled(order, "reservation_expired"));
      metrics.increment("waypoint.order.shortfall", "result", "expired");
      return Map.of("orderId", orderId.toString(), "status", cancelled.status().name(),
          "rowVersion", version, "reason", "reservation_expired");
    }

    Confirmed confirmed = (Confirmed) result;
    Reservation reservation =
        OrderMessages.reservation(confirmed.reservation())
            .orElseThrow(() -> new DomainException(
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                "The warehouse confirmed " + ref + " without usable totals", List.of("STK-07")));
    List<OrderLine> lines =
        confirmed.lines().stream().map(l -> new OrderLine(l.productId(), l.quantity())).toList();
    Order next = order.acceptShortfall(reservation, lines);
    long version = orders.update(next, expected, true, now);
    orders.recordStatus(orderId, Optional.of(order.status()), next.status(),
        "shortfall accepted; warehouse confirmed " + ref, actor.userId(), Optional.empty(), now);
    events.publish(actor, OrderMessages.amended(next));
    metrics.increment("waypoint.order.shortfall", "result", "accepted");
    return Map.of("orderId", orderId.toString(), "status", next.status().name(), "rowVersion", version);
  }
}
