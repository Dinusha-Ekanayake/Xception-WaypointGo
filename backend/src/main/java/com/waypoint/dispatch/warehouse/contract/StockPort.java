package com.waypoint.dispatch.warehouse.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock at the external warehouse, behind the anti-corruption layer.
 *
 * <p>Creating a warehouse order <em>is</em> the reservation (R-STK-08); there is
 * no separate reserve call. Ordering calls {@link #placeOrder} synchronously at
 * placement; every other warehouse call (cancel, shipped, delivered) is made by
 * the Warehouse module from events (decision B2).
 *
 * <p>An implementation must never blindly retry a placement: the warehouse's
 * {@code POST /orders} is not idempotent and carries no client reference, so a
 * retry after an unknown outcome first looks for the earlier order (R-STK-11).
 * When the circuit is open it returns {@link Unavailable} at once.
 */
public interface StockPort {

  /**
   * Places the order in the depot's own warehouse (A-25). Calling it again with
   * the same {@code orderRef} and lines returns the first answer rather than
   * placing twice, which is what makes Ordering's serializable retry safe.
   */
  PlacementResult placeOrder(PlacementRequest request);

  /**
   * Replaces the goods of an existing warehouse order. The warehouse has no amend
   * call, so this places the new lines and cancels the old order once they are
   * reserved. Amendment is strict: a partial answer is released and reported as
   * {@link Insufficient}.
   */
  PlacementResult amendOrder(String warehouseOrderRef, PlacementRequest request);

  /**
   * The store accepts a {@link PartiallyReserved} answer: the locked quantities
   * become the order (revision of D-F, 2026-10-01).
   */
  ConfirmResult confirmReservation(String warehouseOrderRef);

  /**
   * @param orderId Waypoint's order, carried on the events a late answer publishes
   * @param orderRef Waypoint's order reference; one placement per reference
   * @param depotCode the depot, which names the warehouse ({@code Kandy}, {@code Peliyagoda})
   */
  record PlacementRequest(UUID orderId, String orderRef, String depotCode, List<StockLine> lines) {

    public PlacementRequest {
      lines = List.copyOf(lines);
    }
  }

  record StockLine(String productId, int quantity) {}

  sealed interface PlacementResult
      permits Reserved, PartiallyReserved, Insufficient, Rejected, Unavailable {}

  sealed interface ConfirmResult permits Confirmed, Expired, Unavailable {}

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

  /**
   * Some lines are short. What was available is locked at the warehouse until
   * {@code expiresAt}; the store accepts it ({@link #confirmReservation}) or
   * cancels the order. Left alone it expires and the order is cancelled.
   *
   * @param reservation the measures of the locked quantities only
   * @param lines per line: requested, and what is locked as {@code available}
   * @param alternatives what the other warehouse holds of each short product
   */
  record PartiallyReserved(
      Reserved reservation,
      Instant expiresAt,
      List<LineAvailability> lines,
      List<Alternative> alternatives)
      implements PlacementResult {

    public PartiallyReserved {
      lines = List.copyOf(lines);
      alternatives = List.copyOf(alternatives);
    }
  }

  /** Nothing at all was available, so nothing was reserved. */
  record Insufficient(List<LineAvailability> lines) implements PlacementResult {

    public Insufficient {
      lines = List.copyOf(lines);
    }
  }

  record LineAvailability(String productId, int requested, int available) {}

  /** Stock of a short product in the other warehouse. Waypoint never splits an order (A-25). */
  record Alternative(String productId, String warehouse, int available) {}

  /**
   * The warehouse refused the request itself: an unknown product, an invalid
   * quantity, or a reply Waypoint cannot plan (mixed temperature). Nothing is
   * held; any order the warehouse created was cancelled.
   */
  record Rejected(String reason) implements PlacementResult {}

  /** The warehouse could not be reached; the order is {@code STOCK_UNKNOWN} (decision D-G). */
  record Unavailable(String reason) implements PlacementResult, ConfirmResult {}

  /** The partial reservation became a pending order with the locked quantities. */
  record Confirmed(Reserved reservation, List<StockLine> lines) implements ConfirmResult {

    public Confirmed {
      lines = List.copyOf(lines);
    }
  }

  /** The lock ran out, or the order is no longer reserved. Nothing is held. */
  record Expired(String reason) implements ConfirmResult {}
}
