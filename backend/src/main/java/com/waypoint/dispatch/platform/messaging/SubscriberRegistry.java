package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which subscribers want which event type, built once at startup.
 *
 * <p>Fails startup rather than delivering wrongly. Two subscribers with one
 * {@code consumerName} would share an inbox row, so the second would be told the
 * event was already applied and silently skip it; an event record without a
 * {@code TYPE} constant could never be routed. Both are mistakes a deployment
 * should refuse, not discover as a missing side effect.
 */
@Component
public class SubscriberRegistry {
  private final Map<String, List<EventSubscriber<? extends DomainEvent>>> byType;

  public SubscriberRegistry(List<EventSubscriber<? extends DomainEvent>> subscribers) {
    Set<String> names = new HashSet<>();
    Map<String, List<EventSubscriber<? extends DomainEvent>>> routes = new LinkedHashMap<>();
    for (EventSubscriber<? extends DomainEvent> subscriber : subscribers) {
      if (!names.add(subscriber.consumerName())) {
        throw new IllegalStateException(
            "Two subscribers are named " + subscriber.consumerName() + "; consumer names must be unique");
      }
      routes
          .computeIfAbsent(typeOf(subscriber.eventType()), type -> new ArrayList<>())
          .add(subscriber);
    }
    routes.replaceAll((type, list) -> List.copyOf(list));
    this.byType = Map.copyOf(routes);
  }

  /** Empty when nobody listens, which is normal for an event only an outside system reads. */
  public List<EventSubscriber<? extends DomainEvent>> forType(String eventType) {
    return byType.getOrDefault(eventType, List.of());
  }

  static String typeOf(Class<? extends DomainEvent> event) {
    try {
      Field field = event.getField("TYPE");
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
        throw new NoSuchFieldException();
      }
      return (String) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          event.getName() + " declares no public static final String TYPE, so it cannot be routed", e);
    }
  }
}
