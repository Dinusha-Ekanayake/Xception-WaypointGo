package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-memory {@link EventPublisher} for module unit tests, so a handler can be
 * tested for the events it publishes before the outbox relay exists. It records;
 * it delivers nothing.
 */
public final class RecordingEventPublisher implements EventPublisher {

  public record Published(Actor actor, DomainEvent event) {}

  private final List<Published> published = new ArrayList<>();

  @Override
  public void publish(Actor actor, DomainEvent event) {
    published.add(new Published(actor, event));
  }

  public List<Published> published() {
    return List.copyOf(published);
  }

  /** Every published event of one type, in publication order. */
  public <E extends DomainEvent> List<E> eventsOf(Class<E> type) {
    return published.stream().map(Published::event).filter(type::isInstance).map(type::cast).toList();
  }

  public void clear() {
    published.clear();
  }
}
