package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderCancelled;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.shared.domain.Actor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingEventPublisherTest {

  @Test
  void recordsEventsInOrderAndFiltersByType() {
    RecordingEventPublisher publisher = new RecordingEventPublisher();
    Actor actor = Actor.user(UUID.randomUUID());
    OrdersClosed closed = new OrdersClosed("PELIYAGODA", LocalDate.of(2026, 10, 1), List.of());
    OrderCancelled cancelled =
        new OrderCancelled(UUID.randomUUID(), "OUT001", "PELIYAGODA", Optional.empty(), "test");

    publisher.publish(actor, closed);
    publisher.publish(null, cancelled);

    assertEquals(2, publisher.published().size());
    assertEquals(List.of(cancelled), publisher.eventsOf(OrderCancelled.class));
    assertEquals("orders.closed", publisher.published().get(0).event().type());
    assertEquals(1, closed.version());
  }
}
