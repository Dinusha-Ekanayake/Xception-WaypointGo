package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.EtaChanged;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.EtaPolicy;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The vehicle is at the outlet. Waiting and lateness are settled now, from the
 * server's clock against the outlet's window (R-EXE-04, R-EXE-14, R-EXE-10).
 *
 * <p>The arrival also says how far behind plan the trip is running, and the
 * stops still ahead are told when that has moved enough to matter (R-EXE-15).
 * An arrival whose time is uncertain announces nothing: a record replayed hours
 * after the fact says when it was replayed, not how late the trip is.
 */
@Component
public class RecordArrivalHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public RecordArrivalHandler(
      JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics, Clock clock) {
    super(ExecutionCommands.RECORD_ARRIVAL);
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    UUID deliveryId = CommandPayload.of(command).uuid("deliveryId");
    // The device's own reading of the arrival, or failing that when it queued the command.
    Optional<Instant> deviceAt =
        ExecutionMessages.optionalInstant(command, "deviceArrivedAt")
            .or(() -> Optional.ofNullable(command.clientRecordedAt()));
    Instant now = clock.now();

    DeliveryRecord before = ExecutionMessages.load(deliveries, deliveryId, expected);
    DeliveryRecord arrived = before.arrive(now, deviceAt);
    long version = deliveries.save(arrived, expected, ExecutionMessages.stamp(actor, command, now));
    deviceAt.ifPresent(at -> deliveries.rememberDeviceArrival(deliveryId, at));

    if (!arrived.timingUncertain()) {
      announceDelay(actor, arrived, now);
    }

    deviceAt.ifPresent(
        at -> metrics.record("waypoint.execution.clock_skew", Duration.between(at, now).abs().toMillis()));
    metrics.record("waypoint.execution.wait", Duration.ofMinutes(arrived.waitMinutes().orElse(0)).toMillis());
    metrics.increment(
        "waypoint.execution.arrived",
        "late", String.valueOf(arrived.isLate()),
        "uncertain", String.valueOf(arrived.timingUncertain()));
    return ExecutionMessages.result(arrived, version);
  }

  private void announceDelay(Actor actor, DeliveryRecord arrived, Instant now) {
    int delay = EtaPolicy.delayMinutes(now, arrived.plannedArrivalAt());
    if (!EtaPolicy.worthAnnouncing(delay, deliveries.announcedDelay(arrived.tripId()))) {
      return;
    }
    deliveries.announceDelay(arrived.tripId(), delay);
    for (DeliveryRecord ahead : deliveries.pendingOfTrip(arrived.tripId())) {
      Instant expectedArrival = EtaPolicy.expectedArrival(ahead.plannedArrivalAt(), delay);
      deliveries.expect(ahead.deliveryId(), expectedArrival);
      events.publish(
          actor,
          new EtaChanged(ahead.deliveryId(), ahead.orderId(), ahead.outletId(), expectedArrival, delay));
    }
    metrics.increment("waypoint.execution.eta_changed");
  }
}
