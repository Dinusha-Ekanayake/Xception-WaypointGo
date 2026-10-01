package com.waypoint.dispatch.ordering.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Events Ordering publishes. Consumers: Planning, Warehouse, Notification. */
public final class OrderEvents {
  private OrderEvents() {}

  public record OrderPlaced(
      UUID orderId,
      String orderRef,
      String outletId,
      String depotCode,
      String brandCode,
      String districtName,
      LocalDate deliveryDate,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      int itemCount,
      OrderStatus status)
      implements DomainEvent {
    public static final String TYPE = "order.placed";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  public record OrderAmended(
      UUID orderId,
      String outletId,
      String depotCode,
      LocalDate deliveryDate,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      int itemCount)
      implements DomainEvent {
    public static final String TYPE = "order.amended";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  /**
   * @param warehouseOrderRef the reservation Warehouse must cancel; empty when
   *     none was ever made
   */
  public record OrderCancelled(
      UUID orderId,
      String outletId,
      String depotCode,
      Optional<String> warehouseOrderRef,
      String reason)
      implements DomainEvent {
    public static final String TYPE = "order.cancelled";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  /**
   * The cutoff passed while the warehouse had not answered, so the order moved
   * to a later run with reason {@code stock_unresolved} (R-STK-06, STK-03). It
   * is still not demand: stock is never assumed (R-STK-05). Notification tells
   * the store.
   */
  public record OrderAutoDeferred(
      UUID orderId,
      String outletId,
      String depotCode,
      LocalDate fromDate,
      LocalDate toDate,
      String reason)
      implements DomainEvent {
    public static final String TYPE = "order.auto_deferred";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "order";
    }

    @Override
    public String aggregateId() {
      return orderId.toString();
    }
  }

  /** Demand for this depot and day is final; Planning may generate. */
  public record OrdersClosed(String depotCode, LocalDate serviceDate, List<UUID> orderIds)
      implements DomainEvent {
    public static final String TYPE = "orders.closed";

    public OrdersClosed {
      orderIds = List.copyOf(orderIds);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "service_day";
    }

    @Override
    public String aggregateId() {
      return depotCode + ":" + serviceDate;
    }
  }
}
