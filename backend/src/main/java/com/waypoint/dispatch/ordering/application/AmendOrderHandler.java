package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderCommands;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Cutoff;
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
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementRequest;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Rejected;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The store replacing an order's lines, before it is planned.
 *
 * <p>Only {@code STOCK_UNKNOWN} and {@code CONFIRMED} orders amend, and only
 * while their day is still open: after allocation it is a version conflict for
 * the dispatcher to revalidate (ORD-05), after loading it is an issue (ORD-06).
 *
 * <p>The warehouse re-reserves and its totals replace the old ones (R-ORD-12).
 * A confirmed order whose warehouse is unreachable is refused rather than
 * amended locally, because the reservation it holds would then describe
 * different goods from the order. A stock-unknown order has no reservation to
 * disagree with, so it keeps the new lines and stays stock-unknown.
 */
@Component
public class AmendOrderHandler implements CommandHandler {
  private final JdbcOrderRepository orders;
  private final StockPort stock;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public AmendOrderHandler(
      JdbcOrderRepository orders,
      StockPort stock,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.orders = orders;
    this.stock = stock;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return OrderCommands.AMEND;
  }

  @Override
  public String action() {
    return OrderCommands.AMEND;
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
    List<OrderLine> lines = OrderMessages.lines(command);
    Instant now = clock.now();

    Order order = OrderMessages.current(orders, orderId, expected);
    try {
      order.requireAmendable();
    } catch (DomainException e) {
      metrics.increment("waypoint.order.amend_refused", "status", JdbcOrderRepository.code(order.status()));
      throw e;
    }
    if (Cutoff.hasPassed(order.deliveryDate(), now)
        || orders.isClosed(order.depotCode(), order.deliveryDate())) {
      metrics.increment("waypoint.order.amend_refused", "status", "closed");
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Ordering for " + order.deliveryDate() + " has closed; amendments are no longer accepted",
          List.of("R-ORD-01"));
    }

    Optional<Reservation> reservation = reserve(order, lines);
    Order next = order.amend(lines, reservation);
    long version = orders.update(next, expected, true, now);
    String reason = next.status() != order.status() ? "amended; warehouse reserved" : "amended";
    orders.recordStatus(
        orderId, Optional.of(order.status()), next.status(), reason,
        actor.userId(), Optional.empty(), now);
    events.publish(actor, OrderMessages.amended(next));
    metrics.increment("waypoint.order.amended", "status", JdbcOrderRepository.code(next.status()));

    Map<String, Object> body = OrderMessages.answer(next, version);
    body.put("rolledBecause", List.of());
    return body;
  }

  private Optional<Reservation> reserve(Order order, List<OrderLine> lines) {
    boolean confirmed = order.status() == OrderStatus.CONFIRMED;
    PlacementRequest request =
        new PlacementRequest(
            order.orderId(), order.orderRef(), order.depotCode(), OrderMessages.stockLines(lines));
    PlacementResult result =
        confirmed
            ? stock.amendOrder(order.reservation().orElseThrow().warehouseOrderRef(), request)
            : stock.placeOrder(request);
    if (result instanceof Insufficient i) {
      metrics.increment("waypoint.order.rejected", "reason", "insufficient_stock");
      throw OrderMessages.insufficient(i);
    }
    if (result instanceof Rejected rejected) {
      metrics.increment("waypoint.order.rejected", "reason", "warehouse_refused");
      throw new DomainException(ErrorCode.VALIDATION_FAILED, rejected.reason(), List.of("R-STK-08"));
    }
    if (result instanceof PartiallyReserved p) {
      // An amendment keeps nothing short: StockPort#amendOrder answers Insufficient,
      // but placing a stock-unknown order's new lines can still come back partial.
      // That lock is released by the warehouse on expiry; the store resubmits.
      metrics.increment("waypoint.order.rejected", "reason", "insufficient_stock");
      throw OrderMessages.insufficient(new Insufficient(p.lines()));
    }
    if (result instanceof Reserved r) {
      Optional<Reservation> reservation = OrderMessages.reservation(r);
      if (reservation.isPresent() || !confirmed) {
        return reservation;
      }
    }
    if (confirmed) {
      throw new DomainException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          "The warehouse could not re-reserve order " + order.orderRef()
              + "; it is unchanged. Try again when the warehouse is reachable.");
    }
    return Optional.empty();
  }
}
