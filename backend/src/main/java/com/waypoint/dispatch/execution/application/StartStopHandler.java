package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryStarted;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The driver sets off for a stop. Announces delivery.started so the outlet can be told. */
@Component
public class StartStopHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public StartStopHandler(
      JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics, Clock clock) {
    super(ExecutionCommands.START_STOP);
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    UUID deliveryId = CommandPayload.of(command).uuid("deliveryId");
    Instant now = clock.now();

    DeliveryRecord started = ExecutionMessages.load(deliveries, deliveryId, expected).start(now);
    long version = deliveries.save(started, expected, ExecutionMessages.stamp(actor, command, now));

    events.publish(
        actor, new DeliveryStarted(deliveryId, started.orderId(), started.tripId(), started.outletId()));
    metrics.increment("waypoint.execution.started");
    return ExecutionMessages.result(started, version);
  }
}
