package com.waypoint.dispatch.ordering.contract;

/**
 * The one status vocabulary for an order, owned by Ordering.
 *
 * <p>Other modules keep their own state (a trip, a loading check, a delivery
 * record) and never write this one. Ordering moves an order only on its own
 * commands and on events it consumes, which is what keeps one real order from
 * having two opinions about where it is. The legal transitions are enforced in
 * Ordering's domain, not here: a status {@code CHECK} restricts values, not moves.
 *
 * <p>There is no {@code stock_held}, {@code adjusted} or {@code rejected}: a
 * short line rejects placement outright and the store resubmits (decision D-F).
 */
public enum OrderStatus {
  /** Saved while the warehouse was unreachable. Never counts as reserved (D-G). */
  STOCK_UNKNOWN,
  /** Reserved at the warehouse; demand Planning may allocate. */
  CONFIRMED,
  ALLOCATED,
  /** Carried to a later run. The warehouse reservation is kept (D-H). */
  DEFERRED,
  /** Larger than any eligible vehicle; surfaced for a decision, never deferred forever. */
  UNSERVABLE,
  LOADING,
  IN_TRANSIT,
  DELIVERED,
  PARTIALLY_DELIVERED,
  FAILED,
  RECEIVED,
  /** The store did not confirm within the auto-close window (R-RCP-05). */
  UNCONFIRMED,
  CANCELLED
}
