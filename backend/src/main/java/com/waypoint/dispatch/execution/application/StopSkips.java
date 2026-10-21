package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliverySkipped;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.StopSkip;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.Stamp;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R-EXE-31: one stop taken off a trip by the dispatcher, the same way for Skip
 * store and End trip. Runs inside the command's transaction: the stop under its
 * version guard, the decision kept beside it once (rule 8), and
 * {@code delivery.skipped} for Ordering, Messaging and Notification.
 */
@Component
class StopSkips {
  /** One wording for absent and out of scope, as for the driver's stops. */
  static final String NOT_YOURS = "This stop is not at a depot you dispatch";

  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;

  StopSkips(JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics) {
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
  }

  /** The stop, if the actor dispatches its depot; scope is refused before versions are compared. */
  DeliveryRecord load(UUID deliveryId) {
    return deliveries
        .find(deliveryId)
        .filter(stop -> deliveries.oversees(stop.depotCode()))
        .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, NOT_YOURS));
  }

  /** @return the stop's new row version */
  long skip(
      Actor actor, Command command, DeliveryRecord stop, long expected, StopSkip.Then then, String reason,
      boolean wholeTrip, Instant now) {
    if (stop.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, "This stop changed on the road or in the plan. Read the trip again.");
    }
    DeliveryRecord after = stop.skipByDispatcher(now);
    Stamp stamp = ExecutionMessages.stamp(actor, command, now);
    long version = deliveries.save(after, expected, stamp);
    if (!deliveries.insertStopSkip(after.deliveryId(), then.code(), reason, wholeTrip, command.commandId(), stamp)) {
      throw new DomainException(ErrorCode.CONFLICT, "This stop was already taken off the trip");
    }
    events.publish(
        actor,
        new DeliverySkipped(
            after.deliveryId(), after.orderId(), after.tripId(), after.outletId(), after.depotCode(),
            after.vehicleId(), after.serviceDate(), then.name(), reason, wholeTrip, now));
    metrics.increment(
        "waypoint.execution.stop_skipped", "then", then.code(), "whole_trip", String.valueOf(wholeTrip),
        "reached", String.valueOf(stop.arrivedAt().isPresent()));
    return version;
  }
}
