package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;

/**
 * Publishes a domain event through the transactional outbox.
 *
 * <p>Must be called inside the transaction that made the change, typically from
 * a command handler. The event is written to {@code integration.outbox_events}
 * in that same transaction, so the state change, its audit row and its event
 * either all commit or none do. A relay delivers it afterwards, at least once.
 *
 * <p>Never publish from outside a transaction and never call a consumer
 * directly: an event that escapes a transaction that later rolls back announces
 * something that did not happen.
 */
public interface EventPublisher {

  /**
   * @param actor who caused the event; {@code null} for a system actor such as
   *     the scheduler
   */
  void publish(Actor actor, DomainEvent event);
}
