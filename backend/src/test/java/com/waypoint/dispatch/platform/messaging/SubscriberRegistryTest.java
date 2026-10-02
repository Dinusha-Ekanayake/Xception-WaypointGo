package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubscriberRegistryTest {

  record Routed() implements DomainEvent {
    public static final String TYPE = "test.routed";

    public String type() {
      return TYPE;
    }

    public String aggregateType() {
      return "routed";
    }

    public String aggregateId() {
      return "1";
    }
  }

  record Untyped() implements DomainEvent {
    public String type() {
      return "test.untyped";
    }

    public String aggregateType() {
      return "untyped";
    }

    public String aggregateId() {
      return "1";
    }
  }

  record Named<E extends DomainEvent>(String name, Class<E> type) implements EventSubscriber<E> {
    public String consumerName() {
      return name;
    }

    public Class<E> eventType() {
      return type;
    }

    public ModuleRole moduleRole() {
      return ModuleRole.ORDERING;
    }

    public void on(EventEnvelope<E> envelope) {}
  }

  @Test
  void subscribersAreRoutedByTheTypeConstantOfTheirEvent() {
    var registry =
        new SubscriberRegistry(
            List.<EventSubscriber<? extends DomainEvent>>of(
                new Named<>("a", Routed.class), new Named<>("b", Routed.class)));

    assertEquals(2, registry.forType("test.routed").size());
    assertTrue(registry.forType("test.nobody").isEmpty(), "an event nobody reads is not an error");
  }

  @Test
  void twoSubscribersWithOneNameWouldShareAnInboxRowSoStartupRefusesThem() {
    var clash = List.<EventSubscriber<? extends DomainEvent>>of(
        new Named<>("same", Routed.class), new Named<>("same", Routed.class));

    assertThrows(IllegalStateException.class, () -> new SubscriberRegistry(clash));
  }

  @Test
  void anEventWithoutATypeConstantCannotBeRoutedSoStartupRefusesIt() {
    var untyped = List.<EventSubscriber<? extends DomainEvent>>of(new Named<>("u", Untyped.class));

    assertThrows(IllegalStateException.class, () -> new SubscriberRegistry(untyped));
  }
}
