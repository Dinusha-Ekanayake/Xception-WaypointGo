package com.waypoint.dispatch.warehouse.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Events Warehouse publishes. Consumer: Ordering (and Issues for discrepancies). */
public final class WarehouseEvents {
  private WarehouseEvents() {}

  /**
   * The warehouse side of an order changed, or a {@code STOCK_UNKNOWN} order was
   * finally placed.
   *
   * @param orderId Waypoint's order
   * @param status the warehouse status: {@code pending}, {@code shipped},
   *     {@code delivered}, {@code cancelled}, or {@code insufficient} when a
   *     retried placement found stock short
   * @param reservation present when a retried placement succeeded, carrying the
   *     authoritative totals
   */
  public record WarehouseOrderStatusChanged(
      UUID orderId,
      Optional<String> warehouseOrderRef,
      String status,
      Optional<Reserved> reservation)
      implements DomainEvent {
    public static final String TYPE = "warehouse.order_status_changed";

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

  /** Raised by the reconciler; Issues opens a {@code STOCK_DISCREPANCY}. */
  public record WarehouseDiscrepancyFound(
      UUID orderId, String depotCode, String waypointStatus, String warehouseStatus, String detail)
      implements DomainEvent {
    public static final String TYPE = "warehouse.discrepancy_found";

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

  public record CatalogueSynced(String catalogueVersion, int productCount, Instant syncedAt)
      implements DomainEvent {
    public static final String TYPE = "catalogue.synced";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "catalogue";
    }

    @Override
    public String aggregateId() {
      return catalogueVersion;
    }
  }
}
