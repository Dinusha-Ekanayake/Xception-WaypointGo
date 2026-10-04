package com.waypoint.dispatch.warehouse.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The warehouse's order as the adapter read it: the anti-corruption layer's
 * model of the external one. It never leaves this module; Ordering sees only
 * {@code StockPort} results and events.
 *
 * @param warehouseCode {@code KDY} or {@code PLG}
 * @param status {@code reserved}, {@code pending}, {@code shipped},
 *     {@code delivered}, {@code cancelled} or {@code expired}
 * @param temperature {@code chilled}, {@code ambient} or {@code mixed}
 * @param expiresAt present only while {@code reserved}
 * @param items empty in a listing, which carries only the count
 */
public record WarehouseOrder(
    String orderId,
    String status,
    String warehouseCode,
    String temperature,
    BigDecimal totalWeightKg,
    BigDecimal totalVolumeM3,
    Optional<Instant> expiresAt,
    Instant createdAt,
    int itemCount,
    List<Item> items) {

  public WarehouseOrder {
    items = List.copyOf(items);
  }

  /** @param quantity what the warehouse took or locked; {@code requestedQuantity} what was asked */
  public record Item(String productId, int quantity, int requestedQuantity) {}

  /** A short line of a {@code 202}, with what the other warehouse has. */
  public record Shortfall(
      String productId, int requested, int reserved, String otherWarehouse, int otherAvailable) {}

  /** Units, not lines: the count Waypoint keeps as the order's item count. */
  public int units() {
    return items.stream().mapToInt(Item::quantity).sum();
  }
}
