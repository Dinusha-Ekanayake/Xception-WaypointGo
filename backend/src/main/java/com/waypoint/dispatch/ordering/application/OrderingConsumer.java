package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.shared.event.DomainEvent;

/**
 * An event Ordering consumes. The relay opens the transaction as
 * {@code waypoint_ordering} for the system actor, which row-level security lets
 * see every order: an event about an order is not scoped to whoever caused it.
 */
abstract class OrderingConsumer<E extends DomainEvent> implements EventSubscriber<E> {
  protected final OrderTransitions transitions;

  OrderingConsumer(OrderTransitions transitions) {
    this.transitions = transitions;
  }

  @Override
  public final ModuleRole moduleRole() {
    return ModuleRole.ORDERING;
  }
}
