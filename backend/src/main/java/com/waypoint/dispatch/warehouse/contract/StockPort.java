package com.waypoint.dispatch.warehouse.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock at the external warehouse, behind the anti-corruption layer.
 *
 * <p>Creating a warehouse order <em>is</em> the reservation (R-STK-08); there is
 * no separate reserve call and no TTL. Ordering calls {@link #placeOrder}
 * synchronously at placement; every other warehouse call (cancel, shipped,
 * delivered) is made by the Warehouse module from events (decision B2).
 *
 * <p>An implementation must never blindly retry a placement: the warehouse's
 * {@code POST /orders} is not idempotent, so a retry queries first (R-STK-11).
 * When the circuit is open it returns {@link Unavailable} at once.
 */
public interface StockPort {

  /** @param orderRef Waypoint's order reference, used to find an earlier attempt before retrying */
  PlacementResult placeOrder(String orderRef, List<StockLine> lines);

  /** Replace the lines of an existing warehouse order; totals come back as for placement. */
  PlacementResult amendOrder(String warehouseOrderRef, List<StockLine> lines);

  record StockLine(String productId, int quantity) {}

  sealed interface PlacementResult permits Reserved, Insufficient, Unavailable {}

  /**
   * The warehouse accepted and reserved every line. Weight, volume and
   * temperature come from the warehouse and become the order's own,
   * authoritative values (decision D-E).
   */
  record Reserved(
      String warehouseOrderRef,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      String temperature,
      int itemCount)
      implements PlacementResult {}

  /** At least one line is short, so nothing was reserved (decision D-F). */
  record Insufficient(List<LineAvailability> lines) implements PlacementResult {

    public Insufficient {
      lines = List.copyOf(lines);
    }
  }

  record LineAvailability(String productId, int requested, int available) {}

  /** The warehouse could not be reached; the order is {@code STOCK_UNKNOWN} (decision D-G). */
  record Unavailable(String reason) implements PlacementResult {}
}
