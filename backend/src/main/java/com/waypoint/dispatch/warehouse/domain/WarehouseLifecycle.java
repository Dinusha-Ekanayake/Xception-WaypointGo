package com.waypoint.dispatch.warehouse.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The warehouse's order lifecycle and how it lines up with Waypoint's (R-STK-10,
 * R-STK-13). Verified live on 2026-10-01 (A-20):
 *
 * <pre>
 * reserved -&gt; pending | cancelled | expired
 * pending  -&gt; shipped | cancelled
 * shipped  -&gt; delivered
 * </pre>
 *
 * <p>Two state machines describe one real order. Waypoint never forces the
 * warehouse's: an invalid transition is a divergence to raise (STK-10), and a
 * target already reached is success, so a redelivered event is harmless.
 */
public final class WarehouseLifecycle {
  private WarehouseLifecycle() {}

  private static final Map<String, Set<String>> EDGES =
      Map.of(
          "reserved", Set.of("pending", "cancelled", "expired"),
          "pending", Set.of("shipped", "cancelled"),
          "shipped", Set.of("delivered"),
          "delivered", Set.of(),
          "cancelled", Set.of(),
          "expired", Set.of());

  private static final List<String> FORWARD = List.of("reserved", "pending", "shipped", "delivered");

  public static boolean isKnown(String status) {
    return EDGES.containsKey(status);
  }

  public static boolean isTerminal(String status) {
    return EDGES.getOrDefault(status, Set.of()).isEmpty();
  }

  /** What to do to move a warehouse order from {@code current} to {@code target}. */
  public enum Step {
    /** One legal edge: make the call. */
    CALL,
    /** Already there or past it: nothing to do. */
    DONE,
    /** Not reachable: the lifecycles diverged (STK-10). */
    DIVERGED
  }

  public static Step step(String current, String target) {
    if (current.equals(target)) {
      return Step.DONE;
    }
    // An expired reservation holds nothing, which is all a cancel asks for.
    if ("cancelled".equals(target) && "expired".equals(current)) {
      return Step.DONE;
    }
    if (EDGES.getOrDefault(current, Set.of()).contains(target)) {
      return Step.CALL;
    }
    int at = FORWARD.indexOf(current);
    int to = FORWARD.indexOf(target);
    if (at >= 0 && to >= 0 && at > to) {
      return Step.DONE;
    }
    return Step.DIVERGED;
  }

  /**
   * The warehouse statuses consistent with a Waypoint order status. Empty for a
   * Waypoint status the warehouse never sees (stock unknown has no order).
   */
  public static Optional<Set<String>> expectedFor(String waypointStatus) {
    return switch (waypointStatus) {
      case "PARTIALLY_RESERVED" -> Optional.of(Set.of("reserved"));
      case "CONFIRMED", "ALLOCATED", "DEFERRED", "UNSERVABLE", "LOADING" ->
          Optional.of(Set.of("pending"));
      // The release event may still be in flight, so pending is not yet a divergence.
      case "IN_TRANSIT" -> Optional.of(Set.of("pending", "shipped"));
      case "DELIVERED", "PARTIALLY_DELIVERED", "RECEIVED", "UNCONFIRMED" ->
          Optional.of(Set.of("shipped", "delivered"));
      // A failed delivery left the goods shipped; Issues owns what happens next.
      case "FAILED" -> Optional.of(Set.of("shipped"));
      case "CANCELLED" -> Optional.of(Set.of("cancelled", "expired"));
      default -> Optional.empty();
    };
  }
}
