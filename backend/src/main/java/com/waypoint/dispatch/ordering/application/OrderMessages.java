package com.waypoint.dispatch.ordering.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderAmended;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderCancelled;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderPlaced;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Translation at Ordering's edges: command payloads in, warehouse lines out,
 * the warehouse's answer back as a {@link Reservation}, and the aggregate out
 * as the events other modules consume. No decisions are made here.
 */
final class OrderMessages {
  private OrderMessages() {}

  static List<OrderLine> lines(Command command) {
    JsonNode node = command.payload() == null ? null : command.payload().get("lines");
    if (node == null || !node.isArray()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "lines is required");
    }
    List<OrderLine> lines = new ArrayList<>();
    for (JsonNode line : node) {
      JsonNode product = line.get("productId");
      JsonNode quantity = line.get("quantity");
      if (product == null || !product.isTextual() || quantity == null || !quantity.canConvertToInt()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "each line needs a productId and an integer quantity");
      }
      lines.add(new OrderLine(product.asText(), quantity.asInt()));
    }
    return OrderLine.forSubmission(lines);
  }

  /** Every mutation of an existing order names the version it believed it was changing. */
  static long expectedVersion(Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "expectedVersion is required to change an order");
    }
    return command.expectedVersion();
  }

  /**
   * Out of scope and absent look the same, so an id cannot probe another
   * outlet's orders. The stale check here fails before the warehouse is called;
   * the {@code row_version} guard on the write is what actually decides.
   */
  static Order current(
      com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository orders,
      java.util.UUID orderId,
      long expected) {
    Order order =
        orders
            .find(orderId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No order " + orderId));
    if (order.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "Order " + order.orderRef() + " is at version " + order.rowVersion() + ", not " + expected);
    }
    return order;
  }

  static List<StockLine> stockLines(List<OrderLine> lines) {
    return lines.stream().map(l -> new StockLine(l.productId(), l.quantity())).toList();
  }

  /**
   * The warehouse's totals, or empty when they are unusable. A reply with no
   * weight, volume or temperature is never treated as reserved with guessed
   * totals (STK-07): the caller treats it as stock unknown.
   */
  static Optional<Reservation> reservation(Reserved reserved) {
    try {
      return Optional.of(
          new Reservation(
              reserved.warehouseOrderRef(),
              reserved.weightKg(),
              reserved.volumeM3(),
              reserved.temperature(),
              reserved.itemCount()));
    } catch (DomainException | NullPointerException e) {
      return Optional.empty();
    }
  }

  /** What a partial reservation left short, for the store to decide on. */
  static java.util.Map<String, Object> shortfall(
      com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved p) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("warehouseOrderRef", p.reservation().warehouseOrderRef());
    body.put("expiresAt", p.expiresAt().toString());
    body.put(
        "lines",
        p.lines().stream()
            .map(l -> java.util.Map.of(
                "productId", l.productId(), "requested", l.requested(), "reserved", l.available()))
            .toList());
    body.put(
        "otherWarehouse",
        p.alternatives().stream()
            .map(a -> java.util.Map.of(
                "productId", a.productId(), "warehouse", a.warehouse(), "available", a.available()))
            .toList());
    return body;
  }

  /** STK-01: nothing was reserved; say exactly which lines are short and by how much. */
  static DomainException insufficient(Insufficient result) {
    String detail =
        result.lines().stream()
            .filter(l -> l.available() < l.requested())
            .map(l -> l.productId() + " requested " + l.requested() + ", available " + l.available())
            .collect(Collectors.joining("; "));
    return new DomainException(
        ErrorCode.VALIDATION_FAILED,
        "Insufficient stock, nothing was reserved: " + detail,
        List.of("STK-01"));
  }

  static OrderPlaced placed(Order o) {
    Optional<Reservation> r = o.reservation();
    return new OrderPlaced(
        o.orderId(),
        o.orderRef(),
        o.outletId(),
        o.depotCode(),
        o.brandCode(),
        o.districtName(),
        o.deliveryDate(),
        r.map(Reservation::temperature).orElse(null),
        r.map(Reservation::weightKg).orElse(null),
        r.map(Reservation::volumeM3).orElse(null),
        r.map(Reservation::itemCount).orElse(0),
        o.status());
  }

  static OrderAmended amended(Order o) {
    Optional<Reservation> r = o.reservation();
    return new OrderAmended(
        o.orderId(),
        o.outletId(),
        o.depotCode(),
        o.deliveryDate(),
        r.map(Reservation::temperature).orElse(null),
        r.map(Reservation::weightKg).orElse(null),
        r.map(Reservation::volumeM3).orElse(null),
        r.map(Reservation::itemCount).orElse(0));
  }

  static OrderCancelled cancelled(Order o, String reason) {
    return new OrderCancelled(
        o.orderId(),
        o.outletId(),
        o.depotCode(),
        o.reservation().map(Reservation::warehouseOrderRef),
        reason);
  }
}
