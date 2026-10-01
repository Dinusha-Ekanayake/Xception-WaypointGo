package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderCommands;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.OrderRef;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.domain.TemperatureMix;
import com.waypoint.dispatch.ordering.domain.WindowFeasibility;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.AllowanceView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.CatalogueQuery;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * A store manager placing an order.
 *
 * <p>In this order, each step able to refuse before anything is reserved:
 * scope (ORD-09), the outlet's window against its service allowance (R-ORD-10),
 * one temperature per order (R-ORD-06), the delivery date (R-ORD-01, 07, 08),
 * then the warehouse, whose order <em>is</em> the reservation (R-STK-08).
 *
 * <p>The warehouse is called inside the transaction. A serializable retry calls
 * it again with the same reference, derived from the actor and the command id,
 * and the adapter looks that reference up before creating anything (R-STK-11),
 * so a retry finds the first reservation instead of making a second.
 */
@Component
public class PlaceOrderHandler implements CommandHandler {
  private final Database database;
  private final JdbcOrderRepository orders;
  private final ReferenceQuery reference;
  private final DeliveryDateResolver dates;
  private final StockPort stock;
  private final ObjectProvider<CatalogueQuery> catalogue;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public PlaceOrderHandler(
      Database database,
      JdbcOrderRepository orders,
      ReferenceQuery reference,
      DeliveryDateResolver dates,
      StockPort stock,
      ObjectProvider<CatalogueQuery> catalogue,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.orders = orders;
    this.reference = reference;
    this.dates = dates;
    this.stock = stock;
    this.catalogue = catalogue;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return OrderCommands.PLACE;
  }

  @Override
  public String action() {
    return OrderCommands.PLACE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ORDERING;
  }

  @Override
  public String resource(Command command) {
    String outlet = CommandPayload.of(command).text("outletId");
    return outlet == null ? null : "wpt:order:outlet:" + outlet;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String outletId = payload.requiredText("outletId");
    LocalDate requested = payload.date("requestedDate");
    List<OrderLine> lines = OrderMessages.lines(command);
    Instant now = clock.now();

    OutletView outlet =
        reference
            .outlet(outletId, null)
            .orElseThrow(
                () -> new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown outlet " + outletId));
    requireScope(outlet);
    requireServable(outlet);
    requireOneTemperature(lines);

    DeliveryDate date = dates.resolve(outlet.depotCode(), requested, now);
    String orderRef = OrderRef.derive(actor.userId(), command.commandId());

    PlacementResult result = stock.placeOrder(orderRef, OrderMessages.stockLines(lines));
    Optional<Reservation> reservation = Optional.empty();
    String degraded = null;
    if (result instanceof Reserved r) {
      reservation = OrderMessages.reservation(r);
      if (reservation.isEmpty()) {
        degraded = "warehouse returned no usable totals for " + r.warehouseOrderRef();
        metrics.increment("waypoint.order.reservation_unusable");
      }
    } else if (result instanceof Insufficient i) {
      metrics.increment("waypoint.order.rejected", "reason", "insufficient_stock");
      throw OrderMessages.insufficient(i);
    } else {
      degraded = ((Unavailable) result).reason();
    }

    Order order =
        Order.place(
            UuidV7.generate(now, random),
            orderRef,
            outletId,
            outlet.depotCode(),
            outlet.brandCode(),
            outlet.districtName(),
            date,
            reservation,
            lines);
    orders.insert(order, actor.userId(), now, command.commandId(), Optional.empty());
    orders.recordStatus(
        order.orderId(),
        Optional.empty(),
        order.status(),
        degraded == null ? "placed" : "placed; stock unknown: " + degraded,
        actor.userId(),
        Optional.empty(),
        now);
    events.publish(actor, OrderMessages.placed(order));

    metrics.increment("waypoint.order.placed", "status", JdbcOrderRepository.code(order.status()));
    for (String reason : date.reasons()) {
      metrics.increment("waypoint.order.date_rolled", "reason", reason);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", order.orderId().toString());
    body.put("orderRef", order.orderRef());
    body.put("status", order.status().name());
    body.put("requestedDate", requested.toString());
    body.put("deliveryDate", date.delivery().toString());
    body.put("dateRolled", date.rolled());
    body.put("rolledBecause", date.reasons());
    body.put("rowVersion", 1);
    if (order.status() == OrderStatus.STOCK_UNKNOWN) {
      // Degrade visibly: the store is told the stock is unconfirmed and why.
      body.put("degraded", degraded);
    }
    return body;
  }

  /** ORD-09. Raised inside the transaction, where row-level security sees the actor. */
  private void requireScope(OutletView outlet) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT app.actor_has_outlet(?) OR app.actor_has_depot(?) AS ok",
            outlet.outletId(),
            outlet.depotCode());
    if (!Boolean.TRUE.equals(row.get("ok"))) {
      throw new DomainException(
          ErrorCode.FORBIDDEN, "Outlet " + outlet.outletId() + " is outside the actor's scope");
    }
  }

  /** R-ORD-10, ORD-08: refuse at capture, with the arithmetic, not at 04:00. */
  private void requireServable(OutletView outlet) {
    Optional<AllowanceView> allowance =
        reference.serviceAllowance(outlet.brandCode(), outlet.dockType(), null);
    if (allowance.isEmpty()) {
      return;
    }
    Optional<String> violation =
        WindowFeasibility.violation(
            outlet.effectiveWindowOpen(), outlet.effectiveWindowClose(), allowance.get().minutes());
    if (violation.isPresent()) {
      metrics.increment("waypoint.order.rejected", "reason", "window_infeasible");
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Outlet " + outlet.outletId() + " can never be served: " + violation.get(),
          List.of("R-ORD-10"));
    }
  }

  /**
   * R-ORD-06, from the catalogue when there is one. Without it the check is the
   * warehouse's single temperature on the reservation, which is still one per order.
   */
  private void requireOneTemperature(List<OrderLine> lines) {
    CatalogueQuery products = catalogue.getIfAvailable();
    if (products == null) {
      return;
    }
    Map<String, Optional<String>> byProduct = new LinkedHashMap<>();
    for (OrderLine line : lines) {
      byProduct.put(
          line.productId(),
          products.product(line.productId()).flatMap(p -> p.temperature()));
    }
    Optional<String> violation = TemperatureMix.violation(byProduct);
    if (violation.isPresent()) {
      metrics.increment("waypoint.order.rejected", "reason", "temperature_mix");
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, violation.get(), List.of("R-ORD-06", "ORD-03"));
    }
  }
}
