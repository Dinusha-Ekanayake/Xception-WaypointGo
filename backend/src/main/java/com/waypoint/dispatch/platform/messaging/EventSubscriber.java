package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;

/**
 * Reacts to one event type on behalf of one module.
 *
 * <p>The relay runs {@link #on} inside a transaction opened as
 * {@link #moduleRole()}, and records {@code (consumerName, eventId)} in that same
 * transaction. Delivery is at least once, so that record is what makes a
 * redelivery a no-op. A consumer must still be written so that applying an event
 * twice would be harmless, because the record is the second line of defence.
 *
 * <p>A consumer may read its own module's tables and other modules' contract
 * queries. It never reads the producer's tables.
 */
public interface EventSubscriber<E extends DomainEvent> {

  /** Stable and unique, for example {@code ordering.on-plan-published}. Renaming it replays history. */
  String consumerName();

  Class<E> eventType();

  ModuleRole moduleRole();

  void on(EventEnvelope<E> envelope);
}
