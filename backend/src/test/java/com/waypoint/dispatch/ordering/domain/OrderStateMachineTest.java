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
import static com.waypoint.dispatch.ordering.contract.OrderStatus.RECEIVED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.STOCK_UNKNOWN;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.UNCONFIRMED;
import static com.waypoint.dispatch.ordering.contract.OrderStatus.UNSERVABLE;
import static com.waypoint.dispatch.ordering.domain.OrderStateMachine.EventOutcome.APPLY;
import static com.waypoint.dispatch.ordering.domain.OrderStateMachine.EventOutcome.ILLEGAL;
import static com.waypoint.dispatch.ordering.domain.OrderStateMachine.EventOutcome.STALE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderStateMachineTest {

  /** The graph, written out independently so a change to it is a deliberate change here too. */
  private static final Map<OrderStatus, Set<OrderStatus>> LEGAL =
      Map.ofEntries(
          Map.entry(STOCK_UNKNOWN, EnumSet.of(CONFIRMED, DEFERRED, CANCELLED)),
          Map.entry(CONFIRMED, EnumSet.of(ALLOCATED, DEFERRED, UNSERVABLE, CANCELLED)),
          Map.entry(DEFERRED, EnumSet.of(ALLOCATED, DEFERRED, UNSERVABLE, CANCELLED)),
          Map.entry(UNSERVABLE, EnumSet.of(ALLOCATED, DEFERRED, CANCELLED)),
          Map.entry(ALLOCATED, EnumSet.of(LOADING, DEFERRED, UNSERVABLE, CANCELLED)),
          Map.entry(LOADING, EnumSet.of(IN_TRANSIT, DEFERRED)),
          Map.entry(IN_TRANSIT, EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, FAILED)),
          Map.entry(DELIVERED, EnumSet.of(RECEIVED, UNCONFIRMED)),
          Map.entry(PARTIALLY_DELIVERED, EnumSet.of(RECEIVED, UNCONFIRMED)),
          Map.entry(FAILED, EnumSet.noneOf(OrderStatus.class)),
          Map.entry(RECEIVED, EnumSet.noneOf(OrderStatus.class)),
          Map.entry(UNCONFIRMED, EnumSet.noneOf(OrderStatus.class)),
          Map.entry(CANCELLED, EnumSet.noneOf(OrderStatus.class)));

  @Test
  void everyLegalEdgeIsAllowedAndEveryOtherPairIsRefused() {
    for (OrderStatus from : OrderStatus.values()) {
      for (OrderStatus to : OrderStatus.values()) {
        boolean legal = LEGAL.get(from).contains(to);
        assertEquals(legal, OrderStateMachine.isEdge(from, to), from + " -> " + to);
        if (legal) {
          OrderStateMachine.require(from, to);
        } else {
          DomainException refused =
              assertThrows(DomainException.class, () -> OrderStateMachine.require(from, to));
          assertEquals(ErrorCode.CONFLICT, refused.code());
        }
      }
    }
  }

  @Test
  void terminalStatesGoNowhere() {
    for (OrderStatus terminal : OrderStateMachine.TERMINAL) {
      assertTrue(OrderStateMachine.next(terminal).isEmpty(), terminal + " must be terminal");
    }
  }

  @Test
  void cancellationStopsOnceLoadingStarts() {
    for (OrderStatus status : OrderStateMachine.LOADING_OR_LATER) {
      assertFalse(OrderStateMachine.CANCELLABLE.contains(status), status + " is past loading");
    }
    assertTrue(OrderStateMachine.CANCELLABLE.contains(ALLOCATED));
  }

  @Test
  void aRedeliveredEventIsStale() {
    assertEquals(STALE, OrderStateMachine.onEvent(IN_TRANSIT, IN_TRANSIT));
    assertEquals(STALE, OrderStateMachine.onEvent(RECEIVED, RECEIVED));
  }

  @Test
  void aLateEventForAnEarlierStateIsStale() {
    assertEquals(STALE, OrderStateMachine.onEvent(DELIVERED, IN_TRANSIT));
    // A revised plan arriving while the truck is loading does not unload it.
    assertEquals(STALE, OrderStateMachine.onEvent(LOADING, ALLOCATED));
  }

  @Test
  void anEventAheadOfItsPredecessorJumpsForward() {
    // delivery.completed can overtake trip.released: producers are independent.
    assertEquals(APPLY, OrderStateMachine.onEvent(ALLOCATED, DELIVERED));
    assertEquals(APPLY, OrderStateMachine.onEvent(CONFIRMED, LOADING));
  }

  @Test
  void aDeferralIsAppliedEvenThoughTheOrderCouldComeBack() {
    assertEquals(APPLY, OrderStateMachine.onEvent(ALLOCATED, DEFERRED));
    assertEquals(APPLY, OrderStateMachine.onEvent(DEFERRED, DEFERRED));
  }

  @Test
  void anUnreachableTargetIsIllegalNotSilentlyApplied() {
    assertEquals(ILLEGAL, OrderStateMachine.onEvent(CANCELLED, ALLOCATED));
    assertEquals(ILLEGAL, OrderStateMachine.onEvent(FAILED, RECEIVED));
  }
}
