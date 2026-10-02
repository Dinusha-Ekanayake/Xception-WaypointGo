package com.waypoint.dispatch.ordering.domain;

import static com.waypoint.dispatch.ordering.contract.OrderStatus.ALLOCATED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.CANCELLED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.CONFIRMED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.DEFERRED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.DELIVERED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.FAILED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.IN_TRANSIT;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.LOADING;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.PARTIALLY_DELIVERED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.PARTIALLY_RESERVED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.RECEIVED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.STOCK_UNKNOWN;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.UNCONFIRMED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.UNSERVABLE;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The legal moves of an order, and the only place they are defined.
 *
 * <p>A status {@code CHECK} in SQL restricts values, not moves, so this graph is
 * what keeps an order from going backwards or skipping a decision. The graph is
 * MODULES.md "Ordering" state machine, with cancellation refused once loading
 * starts (EDGE-CASES ORD-10).
 *
 * <p>Commands take one edge at a time and fail on anything else. Consumed events
 * are judged by {@link #onEvent}, because delivery is at least once and producers
 * are independent: a {@code delivery.completed} can arrive before the
 * {@code trip.released} that preceded it, and a redelivered event must be a no-op.
 */
public final class OrderStateMachine {
  private OrderStateMachine() {}

  private static final Map<OrderStatus, Set<OrderStatus>> EDGES = new EnumMap<>(OrderStatus.class);

  static {
    EDGES.put(STOCK_UNKNOWN, EnumSet.of(CONFIRMED, DEFERRED, CANCELLED));
    // The store accepts the locked quantities, or cancels; expiry cancels too.
    EDGES.put(PARTIALLY_RESERVED, EnumSet.of(CONFIRMED, CANCELLED));
    EDGES.put(CONFIRMED, EnumSet.of(ALLOCATED, DEFERRED, UNSERVABLE, CANCELLED));
    EDGES.put(DEFERRED, EnumSet.of(ALLOCATED, DEFERRED, UNSERVABLE, CANCELLED));
    EDGES.put(UNSERVABLE, EnumSet.of(ALLOCATED, DEFERRED, CANCELLED));
    EDGES.put(ALLOCATED, EnumSet.of(LOADING, DEFERRED, UNSERVABLE, CANCELLED));
    // A trip deferred as a unit after a failed interchange carries its orders
    // forward with identity intact (MODULES "Vehicle interchange").
    EDGES.put(LOADING, EnumSet.of(IN_TRANSIT, DEFERRED));
    EDGES.put(IN_TRANSIT, EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, FAILED));
    EDGES.put(DELIVERED, EnumSet.of(RECEIVED, UNCONFIRMED));
    EDGES.put(PARTIALLY_DELIVERED, EnumSet.of(RECEIVED, UNCONFIRMED));
    // A failed delivery is never redelivered in place: Issues asks for a new,
    // linked order, so FAILED is terminal for this one.
    EDGES.put(FAILED, EnumSet.noneOf(OrderStatus.class));
    EDGES.put(RECEIVED, EnumSet.noneOf(OrderStatus.class));
    EDGES.put(UNCONFIRMED, EnumSet.noneOf(OrderStatus.class));
    EDGES.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
  }

  /** States a store may still cancel from. Loading or later is refused (ORD-10). */
  public static final Set<OrderStatus> CANCELLABLE =
      EnumSet.of(STOCK_UNKNOWN, PARTIALLY_RESERVED, CONFIRMED, DEFERRED, UNSERVABLE, ALLOCATED);

  /** States a store may amend in. Everything Planning holds is a conflict (ORD-05). */
  public static final Set<OrderStatus> AMENDABLE = EnumSet.of(STOCK_UNKNOWN, CONFIRMED);

  public static final Set<OrderStatus> TERMINAL = EnumSet.of(FAILED, RECEIVED, UNCONFIRMED, CANCELLED);

  /** The physical world has moved on: goods are on a truck or already at the outlet. */
  public static final Set<OrderStatus> LOADING_OR_LATER =
      EnumSet.of(LOADING, IN_TRANSIT, DELIVERED, PARTIALLY_DELIVERED, FAILED, RECEIVED, UNCONFIRMED);

  public static boolean isEdge(OrderStatus from, OrderStatus to) {
    return EDGES.get(from).contains(to);
  }

  /** The legal moves from a state, for tests and for explaining a refusal. */
  public static Set<OrderStatus> next(OrderStatus from) {
    return EnumSet.copyOf(EDGES.get(from));
  }

  /** Throws unless {@code from -> to} is one legal edge. */
  public static void require(OrderStatus from, OrderStatus to) {
    if (!isEdge(from, to)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "An order cannot move from " + from + " to " + to + "; legal next states are " + next(from));
    }
  }

  /** What a consumer should do with an event that asks for {@code to}. */
  public enum EventOutcome {
    /** Apply it. Includes a forward jump over states whose events have not arrived yet. */
    APPLY,
    /** Already there or already past: a redelivery or a late event. Do nothing. */
    STALE,
    /** Not reachable from here, for example a plan allocating a cancelled order. Record it. */
    ILLEGAL
  }

  public static EventOutcome onEvent(OrderStatus current, OrderStatus to) {
    if (isEdge(current, to)) {
      return EventOutcome.APPLY;
    }
    if (current == to) {
      return EventOutcome.STALE;
    }
    // Cancellation is the store's decision and every other module learns it by
    // event. Anything arriving after it, a plan allocating the order or a trip
    // releasing it, is a conflict to record, not history to ignore.
    if (current == CANCELLED) {
      return EventOutcome.ILLEGAL;
    }
    boolean forward = reachable(current, to);
    boolean behind = reachable(to, current);
    if (forward && !behind) {
      return EventOutcome.APPLY;
    }
    if (behind) {
      return EventOutcome.STALE;
    }
    return EventOutcome.ILLEGAL;
  }

  /** True when {@code to} can be reached from {@code from} over one or more legal edges. */
  public static boolean reachable(OrderStatus from, OrderStatus to) {
    Set<OrderStatus> seen = EnumSet.noneOf(OrderStatus.class);
    Deque<OrderStatus> queue = new ArrayDeque<>(List.of(from));
    while (!queue.isEmpty()) {
      for (OrderStatus next : EDGES.get(queue.poll())) {
        if (next == to) {
          return true;
        }
        if (seen.add(next)) {
          queue.add(next);
        }
      }
    }
    return false;
  }
}
